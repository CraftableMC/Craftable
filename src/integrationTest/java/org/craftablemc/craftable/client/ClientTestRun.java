package org.craftablemc.craftable.client;

import org.craftablemc.craftable.Craftable;

/** 中文：只定义范围/批次身份，断言仍由已有两个夹具持有；Gradle/run.ps1 要求本次调用匹配的 START 再 PASS，防止误读旧日志。
 * English: Scope/run identity only; assertions remain in the existing two harnesses.
 * Gradle and tests/run.ps1 require START then PASS for this exact invocation. */
final class ClientTestRun {
    private static final String SUITE = System.getProperty("craftable.clientTestSuite", "unspecified");
    private static final String DIAGNOSTIC = System.getProperty("craftable.clientTestDiagnostic", "none");
    private static final String RUN = System.getProperty("craftable.clientTestRun", "unspecified");

    private ClientTestRun() {}

    static boolean enabledFor(String... suites) {
        for (String suite : suites) if (SUITE.equals(suite)) return true;
        return false;
    }

    static boolean settingsOnly() { return SUITE.equals("settings"); }
    static boolean leaseDiagnostic() { return DIAGNOSTIC.equals("lease"); }
    static boolean diagnostic() { return !DIAGNOSTIC.equals("none"); }
    static void start() { marker("START"); }
    static void pass() { marker("PASS"); }
    static void fail() { marker("FAIL"); }

    static void completedPlanning() {
        if (diagnostic()) Craftable.LOGGER.warn("CLIENT_DIAGNOSTIC PASS: {} observations only; not full planning evidence", DIAGNOSTIC);
        else if (settingsOnly()) Craftable.LOGGER.warn("CLIENT_SETTINGS PASS: settings only; FILTERED, not full planning evidence");
        else Craftable.LOGGER.warn("PLANNING_SMOKE PASS: full planning, including lease-readiness, deferred MAX, and settings regressions");
        pass();
    }

    private static void marker(String result) {
        Craftable.LOGGER.warn("CLIENT_TEST {} suite={} diagnostic={} run={}", result, SUITE, DIAGNOSTIC, RUN);
    }
}
