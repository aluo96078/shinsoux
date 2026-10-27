package dev.shinsou.kmp.plugin.sandbox;

/**
 * Host -> isolated sandbox process (ADR-0002 §4.3). Only bootstraps the process: SandboxProtocol v1
 * frames then flow over the two pipes handed to start(), so there is no binder callback into the
 * host at all; the pipe pair is the package's only channel and its identity.
 */
interface ISandboxProcess {
    /**
     * Installs the seccomp lockdown, then runs the sandbox driver on the pipes (once per process;
     * the process exits when the host closes its end). Returns this process's pid, or a negative
     * code when the sandbox refused to start (no script is ever loaded then).
     */
    int start(in ParcelFileDescriptor fromHost, in ParcelFileDescriptor toHost);

    /** Isolation self-check after start(): a bit per forbidden operation that succeeded (0 = isolated). */
    int probe(String hostPath);
}
