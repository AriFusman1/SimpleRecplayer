package com.pcapplayer.replay;

public enum ReplayState {
    IDLE,      // nothing has been played yet
    WAITING,   // TCP_CLIENT mode: listening, waiting for the service to connect
    PLAYING,
    FINISHED,  // all messages sent
    STOPPED,   // the user pressed Stop
    FAILED;    // network error, see the error message

    /** True when this replay is over and a new one may start. */
    public boolean isDone() {
        return this == IDLE || this == FINISHED || this == STOPPED || this == FAILED;
    }
}
