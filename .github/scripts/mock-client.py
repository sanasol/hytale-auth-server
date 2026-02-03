#!/usr/bin/env python3
"""
Mock Hytale Client for CI Testing

Sends a Connect packet to verify server accepts F2P authentication.

Usage:
    python mock-client.py [--host HOST] [--port PORT] [--token TOKEN]

Protocol:
    - QUIC transport with ALPN "hytale/2"
    - Little-endian byte ordering
    - Packet frame: [4B length LE][4B packet ID LE][payload]
"""

import argparse
import asyncio
import os
import struct
import sys
import tempfile
import uuid
from datetime import datetime, timedelta

try:
    from aioquic.asyncio import connect
    from aioquic.asyncio.protocol import QuicConnectionProtocol
    from aioquic.quic.configuration import QuicConfiguration
    from aioquic.quic.events import StreamDataReceived, ConnectionTerminated, HandshakeCompleted
except ImportError:
    print("[ERROR] aioquic not installed")
    print("[ERROR] Install with: pip install aioquic")
    sys.exit(1)

try:
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.backends import default_backend
except ImportError:
    print("[ERROR] cryptography not installed - required for client certificate")
    print("[ERROR] Install with: pip install cryptography")
    sys.exit(1)

# Packet IDs used in connection flow
PACKET_DISCONNECT = 1
PACKET_AUTH_GRANT = 11
PACKET_CONNECT_ACCEPT = 14

PACKET_NAMES = {
    0: "Connect",
    1: "Disconnect",
    2: "Ping",
    3: "Pong",
    10: "Status",
    11: "AuthGrant",
    12: "AuthToken",
    14: "ConnectAccept",
    15: "PasswordResponse",
    16: "PasswordAccepted",
    17: "PasswordRejected",
}

PROTOCOL_CRC = 1789265863
PROTOCOL_BUILD_NUMBER = 2
CLIENT_VERSION = "1.0.0"


def generate_self_signed_cert():
    """Generate a self-signed client certificate for mTLS."""
    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
        backend=default_backend()
    )

    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "HytaleMockClient"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "MockClient"),
    ])

    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(private_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.utcnow())
        .not_valid_after(datetime.utcnow() + timedelta(days=365))
        .sign(private_key, hashes.SHA256(), default_backend())
    )

    cert_file = tempfile.NamedTemporaryFile(mode='wb', suffix='.pem', delete=False)
    key_file = tempfile.NamedTemporaryFile(mode='wb', suffix='.pem', delete=False)

    cert_file.write(cert.public_bytes(serialization.Encoding.PEM))
    cert_file.close()

    key_file.write(private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.TraditionalOpenSSL,
        encryption_algorithm=serialization.NoEncryption()
    ))
    key_file.close()

    return cert_file.name, key_file.name


class HytalePacket:
    """Hytale packet encoding/decoding utilities."""

    @staticmethod
    def encode_frame(packet_id: int, payload: bytes) -> bytes:
        """Encode packet with frame header: [4B length LE][4B packet ID LE][payload]"""
        header = struct.pack('<II', len(payload), packet_id)
        return header + payload

    @staticmethod
    def decode_frame(data: bytes) -> tuple:
        """Decode packet frame, returns (packet_id, payload, remaining)."""
        if len(data) < 8:
            return None, None, data

        length, packet_id = struct.unpack('<II', data[:8])
        if len(data) < 8 + length:
            return None, None, data

        return packet_id, data[8:8+length], data[8+length:]


class ConnectPacket:
    """
    Packet 0: Connect (Client -> Server)

    Fixed block (66 bytes): nullBits, protocolCrc, protocolBuildNumber,
    clientVersion (20B), clientType, UUID (16B), offset fields (5x4B)

    Variable block (starts at offset 66): username, identityToken, language,
    referralData, referralSource
    """

    PACKET_ID = 0

    def __init__(self,
                 player_uuid: uuid.UUID,
                 username: str,
                 identity_token: str = None,
                 protocol_crc: int = PROTOCOL_CRC,
                 protocol_build: int = PROTOCOL_BUILD_NUMBER,
                 client_version: str = CLIENT_VERSION,
                 client_type: int = 0,
                 language: str = "en"):
        self.player_uuid = player_uuid
        self.username = username
        self.identity_token = identity_token
        self.protocol_crc = protocol_crc
        self.protocol_build = protocol_build
        self.client_version = client_version
        self.client_type = client_type
        self.language = language

    def encode(self) -> bytes:
        """Encode Connect packet payload."""
        null_bits = 0x01 if self.identity_token else 0

        buf = bytearray()
        buf.append(null_bits)
        buf.extend(struct.pack('<I', self.protocol_crc))
        buf.extend(struct.pack('<I', self.protocol_build))
        buf.extend(self.client_version.encode('ascii')[:20].ljust(20, b'\x00'))
        buf.append(self.client_type)
        buf.extend(self.player_uuid.bytes)

        # Offset placeholders (filled in later)
        username_offset_pos = len(buf)
        buf.extend(struct.pack('<I', 0))
        identity_offset_pos = len(buf)
        buf.extend(struct.pack('<I', 0))
        language_offset_pos = len(buf)
        buf.extend(struct.pack('<I', 0))
        referral_data_offset_pos = len(buf)
        buf.extend(struct.pack('<I', 0))
        referral_source_offset_pos = len(buf)
        buf.extend(struct.pack('<I', 0))

        var_block_start = len(buf)
        assert var_block_start == 66, f"Variable block should start at 66, got {var_block_start}"

        # Username
        struct.pack_into('<I', buf, username_offset_pos, len(buf) - var_block_start)
        username_bytes = self.username.encode('ascii')[:16]
        buf.extend(self._encode_varint(len(username_bytes)))
        buf.extend(username_bytes)

        # Identity token (optional)
        if self.identity_token:
            struct.pack_into('<I', buf, identity_offset_pos, len(buf) - var_block_start)
            token_bytes = self.identity_token.encode('utf-8')
            buf.extend(self._encode_varint(len(token_bytes)))
            buf.extend(token_bytes)
        else:
            struct.pack_into('<i', buf, identity_offset_pos, -1)

        # Language
        struct.pack_into('<I', buf, language_offset_pos, len(buf) - var_block_start)
        language_bytes = self.language.encode('ascii')[:16]
        buf.extend(self._encode_varint(len(language_bytes)))
        buf.extend(language_bytes)

        # Unused optional fields
        struct.pack_into('<i', buf, referral_data_offset_pos, -1)
        struct.pack_into('<i', buf, referral_source_offset_pos, -1)

        return HytalePacket.encode_frame(self.PACKET_ID, bytes(buf))

    @staticmethod
    def _encode_varint(value: int) -> bytes:
        """Encode integer as VarInt"""
        result = bytearray()
        while (value & ~0x7F) != 0:
            result.append((value & 0x7F) | 0x80)
            value >>= 7
        result.append(value)
        return bytes(result)


class MockClientProtocol(QuicConnectionProtocol):
    """QUIC protocol handler for mock client."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.handshake_complete = asyncio.Event()
        self.response_received = asyncio.Event()
        self.response_packet_id = None
        self.response_payload = None
        self.error = None
        self._buffer = bytearray()

    def quic_event_received(self, event):
        if isinstance(event, HandshakeCompleted):
            print(f"[QUIC] Handshake completed, ALPN: {event.alpn_protocol}")
            self.handshake_complete.set()
        elif isinstance(event, StreamDataReceived):
            print(f"[QUIC] Stream {event.stream_id} received {len(event.data)} bytes")
            self._buffer.extend(event.data)
            self._process_buffer()
        elif isinstance(event, ConnectionTerminated):
            reason = f", reason={event.reason_phrase}" if event.reason_phrase else ""
            self.error = f"Connection terminated: code={event.error_code}{reason}"
            print(f"[QUIC] {self.error}")
            self.response_received.set()

    def _process_buffer(self):
        """Process received data buffer."""
        while len(self._buffer) >= 8:
            packet_id, payload, remaining = HytalePacket.decode_frame(bytes(self._buffer))
            if packet_id is None:
                break

            self._buffer = bytearray(remaining)
            self.response_packet_id = packet_id
            self.response_payload = payload
            self.response_received.set()

            packet_name = PACKET_NAMES.get(packet_id, f"Unknown({packet_id})")
            print(f"[RECV] {packet_name} (ID: {packet_id}), payload: {len(payload)} bytes")

            if payload:
                hex_preview = payload[:64].hex() + ("..." if len(payload) > 64 else "")
                print(f"[RECV] Payload hex: {hex_preview}")


async def test_connection(host: str, port: int, player_uuid: uuid.UUID,
                          username: str, identity_token: str = None,
                          timeout: float = 10.0) -> dict:
    """Test server connection using QUIC with mTLS client certificate."""
    result = {
        "success": False,
        "host": host,
        "port": port,
        "uuid": str(player_uuid),
        "username": username,
        "has_token": identity_token is not None,
        "response_packet": None,
        "response_name": None,
        "error": None
    }

    print("[INFO] Generating client certificate for mTLS...")
    cert_file, key_file = generate_self_signed_cert()
    print(f"[INFO] Client cert: {cert_file}")

    try:
        config = QuicConfiguration(is_client=True, alpn_protocols=["hytale/2"])
        config.load_cert_chain(cert_file, key_file)
        config.verify_mode = False

        print(f"[INFO] QUIC config: ALPN={config.alpn_protocols}")
        print(f"[INFO] Protocol CRC: {PROTOCOL_CRC}, Build: {PROTOCOL_BUILD_NUMBER}")
        print(f"[INFO] Connecting to {host}:{port}...")

        async with connect(
            host,
            port,
            configuration=config,
            create_protocol=MockClientProtocol,
        ) as protocol:
            protocol: MockClientProtocol

            # Wait for handshake
            print("[INFO] Waiting for QUIC handshake...")
            try:
                await asyncio.wait_for(protocol.handshake_complete.wait(), timeout=timeout)
            except asyncio.TimeoutError:
                result["error"] = f"QUIC handshake timeout ({timeout}s)"
                return result

            print("[INFO] QUIC connection established!")

            # Create and send Connect packet
            connect_packet = ConnectPacket(
                player_uuid=player_uuid,
                username=username,
                identity_token=identity_token,
            )

            packet_data = connect_packet.encode()
            print(f"[SEND] Connect packet, size: {len(packet_data)} bytes")
            print(f"[SEND] Payload hex: {packet_data.hex()[:128]}...")

            # Open a bidirectional stream and send
            stream_id = protocol._quic.get_next_available_stream_id()
            print(f"[QUIC] Using stream ID: {stream_id}")
            protocol._quic.send_stream_data(stream_id, packet_data, end_stream=False)
            protocol.transmit()

            # Wait for response
            print(f"[INFO] Waiting for server response (timeout: {timeout}s)...")
            try:
                await asyncio.wait_for(protocol.response_received.wait(), timeout=timeout)
            except asyncio.TimeoutError:
                result["error"] = f"Response timeout ({timeout}s) - server may have rejected silently"
                return result

            if protocol.error:
                result["error"] = protocol.error
                return result

            packet_id = protocol.response_packet_id
            packet_name = PACKET_NAMES.get(packet_id, f"Unknown({packet_id})")
            result["response_packet"] = packet_id
            result["response_name"] = packet_name

            if packet_id == PACKET_CONNECT_ACCEPT:
                result["success"] = True
                result["message"] = "Server accepted connection (ConnectAccept)"
            elif packet_id == PACKET_AUTH_GRANT:
                result["success"] = True
                result["message"] = "Server sent AuthGrant - authenticated mode working"
            elif packet_id == PACKET_DISCONNECT:
                result["error"] = "Server rejected connection (Disconnect packet)"
                if protocol.response_payload:
                    result["disconnect_payload"] = protocol.response_payload.hex()
            else:
                result["success"] = True
                result["message"] = f"Received {packet_name} (ID: {packet_id})"

            return result

    except ConnectionRefusedError:
        result["error"] = "Connection refused - server not running?"
        return result
    except Exception as e:
        result["error"] = f"{type(e).__name__}: {e}"
        print(f"[ERROR] {result['error']}")
        import traceback
        traceback.print_exc()
        return result
    finally:
        for path in (cert_file, key_file):
            if path and os.path.exists(path):
                os.unlink(path)


def main():
    parser = argparse.ArgumentParser(description="Mock Hytale Client for CI Testing")
    parser.add_argument("--host", default="127.0.0.1", help="Server host")
    parser.add_argument("--port", type=int, default=5520, help="Server port")
    parser.add_argument("--uuid", help="Player UUID (random if not specified)")
    parser.add_argument("--username", default="TestPlayer", help="Player username")
    parser.add_argument("--token", help="F2P identity token")
    parser.add_argument("--token-file", help="File containing identity token")
    parser.add_argument("--timeout", type=float, default=10.0, help="Connection timeout")
    args = parser.parse_args()

    player_uuid = uuid.UUID(args.uuid) if args.uuid else uuid.uuid4()

    identity_token = args.token
    if args.token_file and os.path.exists(args.token_file):
        with open(args.token_file, 'r') as f:
            identity_token = f.read().strip()

    print("=" * 60)
    print("Mock Hytale Client - Connection Test")
    print("=" * 60)
    print(f"Host: {args.host}")
    print(f"Port: {args.port}")
    print(f"UUID: {player_uuid}")
    print(f"Username: {args.username}")
    print(f"Has Token: {identity_token is not None}")
    print(f"Protocol CRC: {PROTOCOL_CRC}")
    print(f"Protocol Build: {PROTOCOL_BUILD_NUMBER}")
    print(f"Timestamp: {datetime.now().isoformat()}")
    print("=" * 60)
    print()

    result = asyncio.run(test_connection(
        args.host, args.port, player_uuid,
        args.username, identity_token, args.timeout
    ))

    print()
    print("=" * 60)
    print("Result")
    print("=" * 60)
    for key, value in result.items():
        if value is not None:
            print(f"{key}: {value}")
    print("=" * 60)

    sys.exit(0 if result.get("success") else 1)


if __name__ == "__main__":
    main()
