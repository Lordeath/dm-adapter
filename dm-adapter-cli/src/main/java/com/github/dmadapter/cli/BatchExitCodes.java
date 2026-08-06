package com.github.dmadapter.cli;

final class BatchExitCodes {
    static final int SUCCESS = 0;
    static final int INTERNAL_ERROR = 1;
    static final int CONFIG_ERROR = 2;
    static final int MANUAL_REVIEW = 3;
    static final int GIT_ERROR = 5;

    private BatchExitCodes() {
    }
}
