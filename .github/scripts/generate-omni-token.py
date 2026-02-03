#!/usr/bin/env python3
"""
Omni-Auth Token Generator for CI Testing

Generates self-signed JWTs with embedded JWK (RFC 7515) for testing the
Omni-Auth decentralized authentication feature.

Usage:
    python generate-omni-token.py --uuid UUID --username NAME [--issuer URL] [--invalid-sig]
"""

import argparse
import base64
import json
import sys
import time
import uuid as uuid_module

try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
except ImportError:
    print("[ERROR] cryptography not installed")
    print("[ERROR] Install with: pip install cryptography")
    sys.exit(1)


def b64url_encode(data: bytes) -> str:
    """Base64url encode without padding."""
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode('ascii')


def b64url_decode(s: str) -> bytes:
    """Base64url decode with padding restoration."""
    padding = 4 - len(s) % 4
    if padding != 4:
        s += '=' * padding
    return base64.urlsafe_b64decode(s)


def generate_ed25519_keypair():
    """Generate an Ed25519 keypair and return (private_key, public_key_bytes, jwk_dict)."""
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key()

    # Get raw public key bytes (32 bytes for Ed25519)
    public_bytes = public_key.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw
    )

    # Get raw private key bytes for JWK (includes private + public for Ed25519)
    private_bytes = private_key.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption()
    )

    # Create JWK with both public and private key (as per Omni-Auth spec)
    jwk = {
        "kty": "OKP",
        "crv": "Ed25519",
        "x": b64url_encode(public_bytes),
        "d": b64url_encode(private_bytes),  # Private key component
        "use": "sig",
        "alg": "EdDSA"
    }

    return private_key, public_bytes, jwk


def create_omni_token(
    player_uuid: str,
    username: str,
    issuer: str = "http://127.0.0.1:12345",
    invalid_signature: bool = False,
    no_embedded_jwk: bool = False
) -> str:
    """
    Create an Omni-Auth JWT token with embedded JWK.

    Args:
        player_uuid: Player's UUID
        username: Player's username
        issuer: Token issuer URL (typically loopback for Omni-Auth)
        invalid_signature: If True, corrupt the signature
        no_embedded_jwk: If True, don't embed JWK in header (for testing rejection)

    Returns:
        JWT string (header.payload.signature)
    """
    private_key, public_bytes, jwk = generate_ed25519_keypair()

    # JWT Header with embedded JWK (RFC 7515)
    header = {
        "alg": "EdDSA",
        "typ": "JWT"
    }

    if not no_embedded_jwk:
        header["jwk"] = jwk

    # JWT Payload (claims)
    now = int(time.time())
    payload = {
        "iss": issuer,
        "sub": player_uuid,
        "aud": "hytale-server",
        "iat": now,
        "exp": now + 3600,  # 1 hour expiry
        "username": username,
        "omni": True  # Flag indicating Omni-Auth token
    }

    # Encode header and payload
    header_b64 = b64url_encode(json.dumps(header, separators=(',', ':')).encode('utf-8'))
    payload_b64 = b64url_encode(json.dumps(payload, separators=(',', ':')).encode('utf-8'))

    # Create signing input
    signing_input = f"{header_b64}.{payload_b64}".encode('ascii')

    # Sign with Ed25519
    signature = private_key.sign(signing_input)

    if invalid_signature:
        # Corrupt the signature by flipping bits
        signature = bytes([b ^ 0xFF for b in signature[:16]]) + signature[16:]

    signature_b64 = b64url_encode(signature)

    return f"{header_b64}.{payload_b64}.{signature_b64}"


def main():
    parser = argparse.ArgumentParser(description="Generate Omni-Auth JWT tokens for testing")
    parser.add_argument("--uuid", required=True, help="Player UUID")
    parser.add_argument("--username", required=True, help="Player username")
    parser.add_argument("--issuer", default="http://127.0.0.1:12345",
                        help="Token issuer URL (default: http://127.0.0.1:12345)")
    parser.add_argument("--invalid-sig", action="store_true",
                        help="Generate token with invalid signature")
    parser.add_argument("--no-jwk", action="store_true",
                        help="Generate token without embedded JWK")
    parser.add_argument("--output", choices=["token", "json", "debug"], default="token",
                        help="Output format")
    args = parser.parse_args()

    # Validate UUID
    try:
        uuid_module.UUID(args.uuid)
    except ValueError:
        print(f"[ERROR] Invalid UUID: {args.uuid}", file=sys.stderr)
        sys.exit(1)

    token = create_omni_token(
        player_uuid=args.uuid,
        username=args.username,
        issuer=args.issuer,
        invalid_signature=args.invalid_sig,
        no_embedded_jwk=args.no_jwk
    )

    if args.output == "token":
        print(token)
    elif args.output == "json":
        print(json.dumps({
            "token": token,
            "uuid": args.uuid,
            "username": args.username,
            "issuer": args.issuer,
            "invalid_signature": args.invalid_sig,
            "has_embedded_jwk": not args.no_jwk
        }))
    elif args.output == "debug":
        # Decode and show token contents
        parts = token.split('.')
        header = json.loads(b64url_decode(parts[0]))
        payload = json.loads(b64url_decode(parts[1]))

        print("=" * 60)
        print("Omni-Auth Token Generated")
        print("=" * 60)
        print(f"UUID: {args.uuid}")
        print(f"Username: {args.username}")
        print(f"Issuer: {args.issuer}")
        print(f"Invalid Signature: {args.invalid_sig}")
        print(f"Has Embedded JWK: {not args.no_jwk}")
        print()
        print("Header:")
        print(json.dumps(header, indent=2))
        print()
        print("Payload:")
        print(json.dumps(payload, indent=2))
        print()
        print("Token:")
        print(token[:80] + "...")
        print("=" * 60)


if __name__ == "__main__":
    main()
