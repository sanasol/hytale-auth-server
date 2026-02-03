const auth = require('../services/auth');
const storage = require('../services/storage');
const metrics = require('../services/metrics');
const { sendJson } = require('../utils/response');

/**
 * Handle telemetry requests from clients
 * Processes heartbeat, session_start, session_end, and event telemetry
 */
async function handleTelemetry(req, res, body, headers) {
  // Extract player UUID from authorization token
  let playerUuid = null;
  if (headers && headers.authorization) {
    const token = headers.authorization.replace('Bearer ', '');
    const tokenData = auth.parseToken(token);
    if (tokenData && tokenData.uuid) {
      playerUuid = tokenData.uuid;
    }
  }

  const telemetryType = body.type;

  // Track telemetry count
  metrics.incCounter('telemetry_received_total');

  // Process based on telemetry type
  if (telemetryType === 'heartbeat' && playerUuid) {
    // Track player as active (even if not on a server)
    await storage.trackActivePlayer(playerUuid, null);

    // Update player's current state with full performance data
    await storage.updatePlayerState(playerUuid, {
      current_state: body.current_state,
      activity_state: body.activity_state,
      game_mode: body.game?.game_mode,
      // Performance metrics
      fps: body.performance?.fps_avg,
      mean_frame_duration_ms: body.performance?.mean_frame_duration_ms,
      frame_time_p99_ms: body.performance?.frame_time_p99_ms,
      draw_calls: body.performance?.draw_calls,
      triangles: body.performance?.triangles,
      // Network metrics
      latency: body.network?.latency_ms,
      connected: body.network?.connected,
      bytes_sent_per_sec: body.network?.sent_bytes_per_second,
      bytes_recv_per_sec: body.network?.received_bytes_per_second,
      // Memory metrics
      memory_mb: body.memory?.working_set_mb,
      private_memory_mb: body.memory?.private_memory_mb,
      gc_collections: body.memory?.gc_gen0_collections,
      // Game metrics
      entity_count: body.game?.entity_count,
      loaded_chunks: body.game?.loaded_chunks,
      view_distance: body.game?.view_distance_effective,
      // Session info
      session_duration_seconds: body.session_duration_seconds,
      session_id: body.session_id
    });

    // Track metrics for histograms
    if (body.performance?.fps_avg) {
      metrics.observeHistogram('fps', body.performance.fps_avg);
    }
    if (body.performance?.frame_time_p99_ms) {
      metrics.observeHistogram('frame_time', body.performance.frame_time_p99_ms);
    }
    if (body.network?.latency_ms) {
      metrics.observeHistogram('latency', body.network.latency_ms);
    }
    if (body.memory?.working_set_mb) {
      metrics.observeHistogram('memory', body.memory.working_set_mb);
    }

    // Track session duration in histogram
    if (body.session_duration_seconds) {
      metrics.observeHistogram('session_duration', body.session_duration_seconds);
    }

    // If player is not connected to any server, remove them from server tracking
    // This handles cases where DELETE /game-session is not sent
    if (body.network?.connected === false) {
      await storage.removePlayerFromAllServers(playerUuid);
    }

  } else if (telemetryType === 'session_start' && playerUuid) {
    // Store hardware info with language
    const language = body.settings?.language || 'unknown';

    await storage.updatePlayerHardware(playerUuid, {
      os: body.platform?.os,
      os_version: body.platform?.os_version,
      architecture: body.platform?.architecture,
      cpu_cores: body.hardware?.cpu_cores,
      system_memory_mb: body.hardware?.system_memory_mb,
      gpu_vendor: body.hardware?.gpu?.vendor,
      gpu_renderer: body.hardware?.gpu?.renderer,
      gpu_vram_mb: body.hardware?.gpu?.vram_available_mb,
      is_low_end: body.hardware?.gpu?.is_low_end,
      resolution: body.display ? `${body.display.resolution_width}x${body.display.resolution_height}` : null,
      refresh_rate: body.display?.refresh_rate_hz,
      display_mode: body.display?.display_mode,
      client_version: body.client?.version,
      client_revision: body.client?.revision_id,
      language: language,
      settings: {
        vsync: body.settings?.vsync,
        fps_limit: body.settings?.fps_limit,
        view_distance: body.settings?.view_distance,
        fov: body.settings?.field_of_view,
        render_scale: body.settings?.render_scale,
        language: language
      },
      machine_id_hash: body.hardware?.machine_id_hash
    });

    // Track language distribution
    metrics.incCounter('language', { language });

  } else if (telemetryType === 'session_end' && playerUuid) {
    // Store session end data for analytics
    const sessionSummary = body.session_summary || {};
    const performanceSummary = body.performance_summary || {};
    const networkSummary = body.network_summary || {};

    await storage.recordSessionEnd(playerUuid, {
      total_duration_seconds: sessionSummary.total_duration_seconds,
      final_state: sessionSummary.final_state,
      exit_reason: sessionSummary.exit_reason,
      was_in_game: sessionSummary.was_in_game,
      total_in_game_seconds: sessionSummary.total_in_game_seconds,
      avg_fps: performanceSummary.avg_fps,
      min_fps: performanceSummary.min_fps,
      max_fps: performanceSummary.max_fps,
      total_frames: performanceSummary.total_frames,
      total_gc_collections: performanceSummary.total_gc_collections,
      total_sent_mb: networkSummary.total_sent_mb,
      total_received_mb: networkSummary.total_received_mb,
      disconnect_count: networkSummary.disconnect_count,
      session_id: body.session_id,
      timestamp: body.timestamp
    });

    // Track exit reason distribution
    if (sessionSummary.exit_reason) {
      metrics.incCounter('exit_reason', { reason: sessionSummary.exit_reason });
    }

    // Track total playtime
    if (sessionSummary.total_duration_seconds) {
      metrics.observeHistogram('session_duration', sessionSummary.total_duration_seconds);
      metrics.addToCounter('total_playtime_seconds', sessionSummary.total_duration_seconds);
    }

    // Track session avg FPS
    if (performanceSummary.avg_fps) {
      metrics.observeHistogram('session_avg_fps', performanceSummary.avg_fps);
    }

    // Session ended - remove player from server tracking
    await storage.removePlayerFromAllServers(playerUuid);

  } else if (telemetryType === 'event' && playerUuid) {
    const eventName = body.event_name;
    const eventData = body.event_data || {};

    // Track event counts
    metrics.incCounter('event', { event: eventName });

    // Store event for analytics
    await storage.recordEvent(playerUuid, {
      event_name: eventName,
      event_data: eventData,
      session_id: body.session_id,
      timestamp: body.timestamp,
      sequence: body.sequence
    });

    // Track specific event metrics
    if (eventName === 'server_connect') {
      if (eventData.time_to_connect_ms) {
        metrics.observeHistogram('connect_time', eventData.time_to_connect_ms);
      }
      metrics.incCounter('server_connect', { success: eventData.success ? 'true' : 'false' });
    } else if (eventName === 'server_disconnect') {
      metrics.incCounter('server_disconnect', { reason: eventData.reason || 'unknown' });
      // Player disconnected from server - remove from tracking
      await storage.removePlayerFromAllServers(playerUuid);
    } else if (eventName === 'world_joined') {
      if (eventData.load_time_ms) {
        metrics.observeHistogram('world_load_time', eventData.load_time_ms);
      }
      metrics.incCounter('world_joined', {
        game_mode: eventData.game_mode || 'unknown',
        singleplayer: eventData.is_singleplayer ? 'true' : 'false'
      });
    } else if (eventName === 'state_transition') {
      metrics.incCounter('state_transition', {
        from: eventData.from_state || 'unknown',
        to: eventData.to_state || 'unknown'
      });
    }
  }

  // Always acknowledge telemetry
  sendJson(res, 200, { success: true, received: true });
}

module.exports = {
  handleTelemetry,
};
