// LitematicRender.exe — portable root launcher.
//
// Replaces 启动.bat / 命令行渲染.bat with a single windowed exe that lives in the extracted root.
//
// Verified semantics (see _launcher-poc/proof/ for the evidence):
//   * Root is derived from the exe's own location (AppDomain.CurrentDomain.BaseDirectory), never
//     from the current working directory, so double-clicking from anywhere works.
//   * ELECTRON_RUN_AS_NODE is REMOVED from the child environment. A parent shell that exports it
//     makes Electron run as plain Node and crash with
//     "Cannot read properties of undefined (reading 'handle')".
//   * Electron is started as  electron.exe "<root>\webapp"  plus any forwarded argv.
//   * The child's stdout/stderr are captured to <root>\logs\launcher.log; a MessageBox surfaces
//     the tail on a non-zero exit, which is what the .bat got from `start /wait` + the console.
//   * `--cli` routes to runtime\bin\java.exe and relays its output to whatever stdout the caller
//     gave us, falling back to AttachConsole only when there is no inherited handle.
//   * Relative file arguments are absolutized against the caller's CWD in GUI mode, because the
//     launcher sets Electron's working directory to the app root.
//
// Build (no SDK required, ships with Windows):
//   C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /target:winexe /platform:x64
//     /optimize+ /win32icon:app.ico /out:LitematicRender.exe Launcher.cs

using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;

[assembly: AssemblyTitle("投影预览")]
[assembly: AssemblyProduct("投影预览")]
[assembly: AssemblyCompany("kinda sus")]
[assembly: AssemblyDescription("Minecraft 投影预览")]
[assembly: AssemblyVersion("1.1.1.2")]
[assembly: AssemblyFileVersion("1.1.1.2")]
[assembly: AssemblyInformationalVersion("1.1.1.2")]

internal static class Launcher
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetEnvironmentStringsW();

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern bool FreeEnvironmentStringsW(IntPtr environment);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool SetEnvironmentVariableW(string name, string value);

    // Some shells supply PATH and Path as separate native entries. Framework's case-insensitive
    // ProcessStartInfo.EnvironmentVariables throws on that block, before either engine can start.
    // Normalize only duplicate entries in this launcher's own process; the parent is unchanged.
    private static void NormalizeInheritedEnvironment()
    {
        IntPtr block = GetEnvironmentStringsW();
        if (block == IntPtr.Zero) return;
        var entries = new Dictionary<string, List<KeyValuePair<string, string>>>(StringComparer.OrdinalIgnoreCase);
        try
        {
            IntPtr cursor = block;
            while (true)
            {
                string entry = Marshal.PtrToStringUni(cursor);
                if (string.IsNullOrEmpty(entry)) break;
                cursor = IntPtr.Add(cursor, (entry.Length + 1) * 2);
                int split = entry.IndexOf('=');
                if (split <= 0) continue;
                string name = entry.Substring(0, split), value = entry.Substring(split + 1);
                List<KeyValuePair<string, string>> group;
                if (!entries.TryGetValue(name, out group))
                {
                    group = new List<KeyValuePair<string, string>>();
                    entries.Add(name, group);
                }
                group.Add(new KeyValuePair<string, string>(name, value));
            }
        }
        finally { FreeEnvironmentStringsW(block); }
        foreach (var group in entries.Values)
        {
            if (group.Count < 2) continue;
            foreach (var entry in group) SetEnvironmentVariableW(entry.Key, null);
            if (!SetEnvironmentVariableW(group[0].Key, group[0].Value))
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        }
    }
    // ---------------------------------------------------------------- native interop

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = false)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(uint dwProcessId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GetStdHandle(int nStdHandle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetStdHandle(int nStdHandle, IntPtr hHandle);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateFileW(string lpFileName, uint dwDesiredAccess,
        uint dwShareMode, ref SECURITY_ATTRIBUTES lpSecurityAttributes, uint dwCreationDisposition,
        uint dwFlagsAndAttributes, IntPtr hTemplateFile);

    [StructLayout(LayoutKind.Sequential)]
    private struct SECURITY_ATTRIBUTES
    {
        public int nLength;
        public IntPtr lpSecurityDescriptor;
        public bool bInheritHandle;
    }

    private const int STD_OUTPUT_HANDLE = -11;
    private const int STD_ERROR_HANDLE = -12;
    private const uint GENERIC_READ = 0x80000000u;
    private const uint GENERIC_WRITE = 0x40000000u;
    private const uint FILE_SHARE_READ = 0x00000001u;
    private const uint FILE_SHARE_WRITE = 0x00000002u;
    private const uint OPEN_EXISTING = 3u;

    private const uint MB_OK = 0x00000000u;
    private const uint MB_ICONERROR = 0x00000010u;
    private const uint MB_SETFOREGROUND = 0x00010000u;
    private const uint ATTACH_PARENT_PROCESS = 0xFFFFFFFFu;


    private static string _root;

    // ---------------------------------------------------------------------- entry point

    [STAThread]
    private static int Main(string[] rawArgs)
    {
        _root = ResolveRoot();

        // Environment.GetCommandLineArgs() is used instead of Main's args so that a forwarded
        // argument containing '=' or a path that looks like a switch is never reinterpreted.
        string[] argv = StripExeName(Environment.GetCommandLineArgs());
        bool cli = argv.Length > 0 && string.Equals(argv[0], "--cli", StringComparison.OrdinalIgnoreCase);

        // Capture the caller's directory before anything changes it; GUI mode needs it to resolve
        // relative file arguments the way a command-line user expects.
        string callerCwd = Directory.GetCurrentDirectory();

        try
        {
            NormalizeInheritedEnvironment();
            // CLI mode leaves argv alone: it does not change the process working directory, so the
            // JVM resolves relative paths against the caller's CWD natively, exactly as the .bat did.
            return cli ? RunCli(argv) : RunGui(AbsolutizeArguments(argv, callerCwd));
        }
        catch (Exception ex)
        {
            return Fail(cli, "Unexpected launcher failure", ex.ToString(), -1);
        }
    }

    /// <summary>
    /// The extracted root is the directory holding this exe. BaseDirectory is used rather than
    /// Assembly.Location because it stays correct for single-file hosts and never depends on the
    /// process working directory.
    /// </summary>
    private static string ResolveRoot()
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        if (string.IsNullOrEmpty(dir))
        {
            Assembly asm = Assembly.GetEntryAssembly();
            if (asm != null && !string.IsNullOrEmpty(asm.Location))
            {
                dir = Path.GetDirectoryName(asm.Location);
            }
        }
        if (string.IsNullOrEmpty(dir)) dir = Directory.GetCurrentDirectory();
        return dir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
    }

    private static string[] StripExeName(string[] commandLineArgs)
    {
        if (commandLineArgs == null || commandLineArgs.Length <= 1) return new string[0];
        string[] rest = new string[commandLineArgs.Length - 1];
        Array.Copy(commandLineArgs, 1, rest, 0, rest.Length);
        return rest;
    }

    /// <summary>
    /// Rewrites relative file arguments to absolute paths before they are handed to Electron.
    ///
    /// The launcher starts Electron with its working directory set to the app root, so a bare
    /// "schematic.litematic" would otherwise resolve against the app root instead of the directory
    /// the user was actually standing in. 命令行渲染.bat had the opposite behaviour (it never
    /// changed directory, so the JVM resolved relative arguments against the caller's CWD); this
    /// keeps that intent.
    ///
    /// Only arguments that (a) are not already rooted and (b) genuinely exist relative to the
    /// launcher's own working directory are touched, so switches such as --dev or --cli and
    /// app-relative resource names pass through untouched.
    /// </summary>
    private static string[] AbsolutizeArguments(string[] argv, string callerCwd)
    {
        string[] result = new string[argv.Length];
        for (int i = 0; i < argv.Length; i++)
        {
            string a = argv[i];
            result[i] = a;
            if (string.IsNullOrEmpty(a)) continue;
            if (a[0] == '-') continue;                       // a switch, never a path
            if (Path.IsPathRooted(a)) continue;              // already absolute
            try
            {
                string candidate = Path.GetFullPath(Path.Combine(callerCwd, a));
                if (File.Exists(candidate) || Directory.Exists(candidate)) result[i] = candidate;
            }
            catch (ArgumentException) { /* illegal path chars: leave it alone */ }
            catch (NotSupportedException) { }
            catch (PathTooLongException) { }
        }
        return result;
    }

    // ------------------------------------------------------------------------ GUI mode

    private static int RunGui(string[] argv)
    {
        string electron = Path.Combine(_root, @"webapp\node_modules\electron\dist\electron.exe");
        string appDir = Path.Combine(_root, "webapp");

        if (!File.Exists(electron))
        {
            return Fail(false, "投影预览 — engine missing",
                "Electron was not found at:\r\n\r\n" + electron + "\r\n\r\n" +
                "The package looks incomplete. Re-extract the whole zip; the folder must contain " +
                "webapp\\, runtime\\, bin\\ and assets\\ next to this exe.", 2);
        }
        if (!Directory.Exists(appDir))
        {
            return Fail(false, "投影预览 — shell missing",
                "The Electron app directory was not found at:\r\n\r\n" + appDir, 2);
        }

        ProcessStartInfo psi = new ProcessStartInfo(electron);
        psi.UseShellExecute = false;          // required for EnvironmentVariables + redirection
        psi.WorkingDirectory = _root;         // see report: CWD is not load-bearing, but a stable
                                              // value keeps relative engine output predictable
        psi.CreateNoWindow = true;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;

        // ---- THE CRITICAL LINE -------------------------------------------------------
        // Remove, do not blank: Electron's native code tests for the variable's presence, so an
        // empty-but-present value is not guaranteed to be treated as "unset".
        psi.EnvironmentVariables.Remove("ELECTRON_RUN_AS_NODE");
        // -----------------------------------------------------------------------------

        psi.Arguments = BuildArguments(appDir, argv);


        string tail = "";
        int code;
        try
        {
            code = Launch(electron, psi, out tail);
        }
        catch (Exception ex)
        {
            return Fail(false, "投影预览 — could not start",
                "Failed to start Electron:\r\n\r\n" + electron + "\r\n\r\n" + ex.Message, -1);
        }

        if (code != 0)
        {
            return Fail(false, "投影预览 — exited with code " + code,
                "Electron exited with code " + code + ".\r\n\r\n" +
                (tail.Length > 0 ? "Last output:\r\n\r\n" + tail : "No diagnostic output was captured.") +
                "\r\n\r\nFull log: " + LogPath(), code);
        }
        return 0;
    }

    /// <summary>
    /// Mirrors package.json's "main": the app directory is the <c>app</c> argument, then any
    /// forwarded argv (a .litematic path, --dev, …) follow it.
    /// </summary>
    private static string BuildArguments(string appDir, string[] argv)
    {
        string args = Quote(appDir);
        for (int i = 0; i < argv.Length; i++)
        {
            args += " " + Quote(argv[i]);
        }
        return args;
    }

    // ------------------------------------------------------------------------ CLI mode

    private static int RunCli(string[] argv)
    {
        string java = Path.Combine(_root, @"runtime\bin\java.exe");
        if (!File.Exists(java))
        {
            // Development fallback, mirroring 命令行渲染.bat.
            string sibling = Path.GetFullPath(Path.Combine(_root, @"..\..\jdk-25.0.4.1+1\bin\java.exe"));
            if (File.Exists(sibling)) java = sibling;
        }
        if (!File.Exists(java))
        {
            return Fail(true, "java runtime missing",
                "Neither runtime\\bin\\java.exe nor a workspace JDK was found under:\r\n\r\n" + _root, 2);
        }

        ProcessStartInfo psi = new ProcessStartInfo(java);
        psi.UseShellExecute = false;
        psi.WorkingDirectory = _root;

        // Redirect ALWAYS, then relay to our own stdout/stderr ourselves.
        //
        // Simply leaving these false does not work: .NET's Process.Start creates the child with
        // bInheritHandles = FALSE when no redirection is requested, so java receives no std handles
        // at all and every progress line and error message is silently discarded (measured — see
        // proof/hd2-direct.log, where the child's output vanished despite a cmd-level 1> redirect).
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;

        // Same reasoning as GUI mode: never let a stale value leak into the JVM.
        psi.EnvironmentVariables.Remove("ELECTRON_RUN_AS_NODE");

        string args = "-Xmx4g -Dfile.encoding=UTF-8 -Djava.net.useSystemProxies=true " +
                      Quote("-Dlitematicrender.home=" + _root + Path.DirectorySeparatorChar) +
                      " -cp " + Quote(Path.Combine(_root, "bin")) +
                      " io.github.litematicrender.Main --render";
        for (int i = 1; i < argv.Length; i++) args += " " + Quote(argv[i]);
        psi.Arguments = args;


        EnsureConsoleForCli();

        try
        {
            string unused;
            return Launch(java, psi, out unused, true);
        }
        catch (Exception ex)
        {
            return Fail(true, "could not start java", ex.Message, -1);
        }
    }

    /// <summary>
    /// Gives CLI mode somewhere to write when the caller left us no stdout.
    ///
    /// Measured behaviour of a /target:winexe process (see proof/handle-probe.txt):
    ///   * launched directly             -> std handles are the caller's pipe/file (usable)
    ///   * launched via `start [ /wait ]` -> std handles are NULL
    /// The inherited handles therefore win, and AttachConsole is only the fallback for the NULL
    /// case. Attaching unconditionally would hijack a perfectly good pipe and divert output to the
    /// real console, where a caller capturing stdout would never see it.
    /// </summary>
    private static void EnsureConsoleForCli()
    {
        IntPtr inherited = GetStdHandle(STD_OUTPUT_HANDLE);
        if (inherited != IntPtr.Zero && inherited != new IntPtr(-1))
        {
            return;   // caller gave us a usable stdout; leave it alone
        }

        if (!AttachConsole(ATTACH_PARENT_PROCESS)) return;

        // With no inherited handle we now have a real console; install CONOUT$ as stdout/stderr so
        // the relay below has a destination.
        SECURITY_ATTRIBUTES sa = new SECURITY_ATTRIBUTES();
        sa.nLength = Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES));
        sa.bInheritHandle = true;
        IntPtr conout = CreateFileW("CONOUT$", GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE, ref sa, OPEN_EXISTING, 0u, IntPtr.Zero);
        if (conout == new IntPtr(-1)) return;

        SetStdHandle(STD_OUTPUT_HANDLE, conout);
        SetStdHandle(STD_ERROR_HANDLE, conout);

        try
        {
            StreamWriter so = new StreamWriter(Console.OpenStandardOutput());
            so.AutoFlush = true;
            Console.SetOut(so);
            StreamWriter se = new StreamWriter(Console.OpenStandardError());
            se.AutoFlush = true;
            Console.SetError(se);
        }
        catch (IOException) { /* no usable console; harmless */ }
    }

    // ------------------------------------------------------------------- process plumbing

    private static int Launch(string exe, ProcessStartInfo psi, out string tail)
    {
        return Launch(exe, psi, out tail, false);
    }

    /// <summary>
    /// Runs a child to completion.
    /// </summary>
    /// <param name="relay">
    /// When true the child's stdout/stderr are written through to this process's own console in
    /// real time, which is what makes <c>--cli</c> behave like the .bat it replaces. Log-file
    /// capture still happens either way.
    /// </param>
    private static int Launch(string exe, ProcessStartInfo psi, out string tail, bool relay)
    {
        System.Text.StringBuilder captured = new System.Text.StringBuilder();
        TextWriter consoleOut = relay ? Console.Out : null;
        TextWriter consoleErr = relay ? Console.Error : null;

        Process p = new Process();
        p.StartInfo = psi;
        p.OutputDataReceived += delegate(object s, DataReceivedEventArgs e)
        {
            if (e.Data == null) return;
            lock (captured) { captured.AppendLine(e.Data); Trim(captured); }
            if (consoleOut != null)
            {
                try { consoleOut.WriteLine(e.Data); } catch (IOException) { }
            }
        };
        p.ErrorDataReceived += delegate(object s, DataReceivedEventArgs e)
        {
            if (e.Data == null) return;
            lock (captured) { captured.AppendLine(e.Data); Trim(captured); }
            if (consoleErr != null)
            {
                try { consoleErr.WriteLine(e.Data); } catch (IOException) { }
            }
        };

        Log("start: " + exe);
        Log("args : " + psi.Arguments);
        Log("cwd  : " + psi.WorkingDirectory);
        Log("ELECTRON_RUN_AS_NODE present in child env: " +
            (psi.EnvironmentVariables.ContainsKey("ELECTRON_RUN_AS_NODE") ? "YES (bug)" : "no"));

        p.Start();
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        p.WaitForExit();
        p.WaitForExit();   // documented second call: lets the async readers drain

        tail = captured.ToString().Trim();
        if (tail.Length > 0) Log(tail);
        Log("exit : " + p.ExitCode);
        return p.ExitCode;
    }

    /// <summary>Keeps the captured diagnostic tail bounded.</summary>
    private static void Trim(System.Text.StringBuilder sb)
    {
        if (sb.Length > 8000) sb.Remove(0, sb.Length - 6000);
    }

    /// <summary>Windows command-line quoting (the rules CommandLineToArgvW applies).</summary>
    private static string Quote(string arg)
    {
        if (arg.Length > 0 && arg.IndexOfAny(new char[] { ' ', '\t', '\n', '\v', '"' }) < 0) return arg;

        System.Text.StringBuilder sb = new System.Text.StringBuilder();
        sb.Append('"');
        int backslashes = 0;
        for (int i = 0; i < arg.Length; i++)
        {
            char c = arg[i];
            if (c == '\\') { backslashes++; continue; }
            if (c == '"')
            {
                sb.Append('\\', backslashes * 2 + 1);
                sb.Append('"');
            }
            else
            {
                sb.Append('\\', backslashes);
                sb.Append(c);
            }
            backslashes = 0;
        }
        sb.Append('\\', backslashes * 2);
        sb.Append('"');
        return sb.ToString();
    }

    // ----------------------------------------------------------------------- diagnostics

    private static string LogPath()
    {
        return Path.Combine(_root, @"logs\launcher.log");
    }

    private static void Log(string line)
    {
        try
        {
            string path = LogPath();
            string dir = Path.GetDirectoryName(path);
            if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);
            File.AppendAllText(path,
                DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + "  " + line + Environment.NewLine);
        }
        catch (Exception) { /* a read-only install location must not break launching */ }
    }

    /// <summary>
    /// Reports a failure the way each mode can actually show it: a MessageBox for the windowed
    /// path (there is no console to print a stack to), stderr for the console path.
    /// </summary>
    private static int Fail(bool console, string caption, string detail, int code)
    {
        Log("FAIL [" + caption + "] " + detail);
        if (console)
        {
            EnsureConsoleForCli();
            try
            {
                Console.Error.WriteLine();
                Console.Error.WriteLine("[ERROR] " + caption);
                Console.Error.WriteLine(detail);
            }
            catch (IOException) { }
        }
        else
        {
            try
            {
                MessageBoxW(IntPtr.Zero, detail, caption,
                    MB_OK | MB_ICONERROR | MB_SETFOREGROUND);
            }
            catch (Exception) { }
        }
        return code;
    }
}
