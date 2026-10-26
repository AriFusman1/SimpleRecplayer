package com.pcapplayer.replay;

/**
 * Snapshot of the current replay, returned by GET /api/replay/status and polled by the UI every second.
 *
 * @param target          "host:port" we send to, or "listening on port N" in TCP_CLIENT mode
 * @param elapsedMillis   time since the first message timer started
 * @param remainingMillis time until the last message is due
 * @param recordingClock  the moment in the original recording that matches "now" (epoch millis), or 0
 * @param error           error message when state is FAILED, otherwise null
 */
public record ReplayStatus(ReplayState state, String recordId, String recordName, String mode, String target,
                           int listenPort, int sent, int total, long elapsedMillis, long remainingMillis,
                           long recordingClock, String error) {

    public static ReplayStatus idle() {
        return new ReplayStatus(ReplayState.IDLE, null, null, null, null, 0, 0, 0, 0, 0, 0, null);
    }
}
