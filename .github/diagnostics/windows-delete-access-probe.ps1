$ErrorActionPreference = 'Stop'
$probeRoot = Join-Path $env:RUNNER_TEMP ('restricted-delete-probe-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $probeRoot | Out-Null

try {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

public static class RestrictedDeleteProbe {
    [StructLayout(LayoutKind.Sequential)]
    private struct SidAndAttributes { public IntPtr Sid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)]
    private struct TokenGroupsFirst { public uint Count; public SidAndAttributes First; }
    [StructLayout(LayoutKind.Sequential)]
    private struct StartupInfo {
        public uint Size;
        public IntPtr Reserved, Desktop, Title;
        public uint X, Y, Width, Height, XChars, YChars, Fill, Flags;
        public ushort Show, ReservedSize;
        public IntPtr ReservedData, Input, Output, Error;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInfo { public IntPtr Process, Thread; public uint ProcessId, ThreadId; }
    [StructLayout(LayoutKind.Sequential)]
    private struct DefaultDacl { public IntPtr Dacl; }

    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError=true)] private static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    [DllImport("advapi32.dll", SetLastError=true)]
    private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError=true)]
    private static extern bool GetTokenInformation(IntPtr token, int informationClass,
        IntPtr information, uint size, out uint required);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(string sddl,
        uint revision, out IntPtr descriptor, IntPtr size);
    [DllImport("advapi32.dll", SetLastError=true)]
    private static extern bool GetSecurityDescriptorDacl(IntPtr descriptor, out bool present,
        out IntPtr dacl, out bool defaulted);
    [DllImport("advapi32.dll", SetLastError=true)]
    private static extern bool SetTokenInformation(IntPtr token, int informationClass,
        ref DefaultDacl information, uint size);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CreateProcessAsUserW(IntPtr token, string application,
        StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity, bool inherit,
        uint flags, IntPtr environment, string cwd, ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint exit);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool TerminateProcess(IntPtr process, uint exit);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool ConvertStringSidToSidW(string text, out IntPtr sid);
    [DllImport("advapi32.dll", SetLastError=true)]
    private static extern bool CreateRestrictedToken(IntPtr source, uint flags, uint disableCount,
        IntPtr disabled, uint privilegeCount, IntPtr privileges, uint restrictedCount,
        [In] SidAndAttributes[] restricted, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError=true)]
    private static extern bool ImpersonateLoggedOnUser(IntPtr token);
    [DllImport("advapi32.dll", SetLastError=true)] private static extern bool RevertToSelf();
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern IntPtr CreateFileW(string path, uint access, uint share, IntPtr security,
        uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool DeleteFileW(string path);

    private static void Require(bool result, string operation) {
        if (!result) {
            int error = Marshal.GetLastWin32Error();
            throw new Win32Exception(error, operation + " failed with Win32 error " + error);
        }
    }

    private static int OpenError(string path, uint access, bool directory) {
        IntPtr handle = CreateFileW(path, access, 7, IntPtr.Zero, 3,
            directory ? 0x02000000u : 0x80u, IntPtr.Zero);
        if (handle == new IntPtr(-1)) return Marshal.GetLastWin32Error();
        Require(CloseHandle(handle), "CloseHandle(file)");
        return 0;
    }

    public static string LogonSid() {
        IntPtr token = IntPtr.Zero;
        IntPtr buffer = IntPtr.Zero;
        try {
            Require(OpenProcessToken(GetCurrentProcess(), 8, out token), "OpenProcessToken(logon SID)");
            uint required;
            bool sized = GetTokenInformation(token, 28, IntPtr.Zero, 0, out required);
            if (sized || Marshal.GetLastWin32Error() != 122)
                throw new Win32Exception(Marshal.GetLastWin32Error(), "GetTokenInformation(TokenLogonSid) size");
            int offset = Marshal.OffsetOf(typeof(TokenGroupsFirst), "First").ToInt32();
            if (required < offset + Marshal.SizeOf(typeof(SidAndAttributes)))
                throw new InvalidOperationException("TokenLogonSid buffer is too small");
            buffer = Marshal.AllocHGlobal(checked((int)required));
            Require(GetTokenInformation(token, 28, buffer, required, out required), "GetTokenInformation(TokenLogonSid)");
            if (Marshal.ReadInt32(buffer) != 1)
                throw new InvalidOperationException("TokenLogonSid did not return exactly one group");
            SidAndAttributes group = Marshal.PtrToStructure<SidAndAttributes>(IntPtr.Add(buffer, offset));
            if (group.Sid == IntPtr.Zero || (group.Attributes & 0xC0000000u) != 0xC0000000u)
                throw new InvalidOperationException("TokenLogonSid did not return a logon group");
            return new System.Security.Principal.SecurityIdentifier(group.Sid).Value;
        } finally {
            if (buffer != IntPtr.Zero) Marshal.FreeHGlobal(buffer);
            if (token != IntPtr.Zero) Require(CloseHandle(token), "CloseHandle(logon SID token)");
        }
    }

    private static void SetDefaultDacl(IntPtr token, string logonSid) {
        IntPtr descriptor = IntPtr.Zero;
        try {
            Require(ConvertStringSecurityDescriptorToSecurityDescriptorW(
                "D:(A;;GA;;;" + logonSid + ")(A;;RC;;;OW)", 1, out descriptor, IntPtr.Zero),
                "ConvertStringSecurityDescriptorToSecurityDescriptorW");
            bool present, defaulted;
            IntPtr dacl;
            Require(GetSecurityDescriptorDacl(descriptor, out present, out dacl, out defaulted),
                "GetSecurityDescriptorDacl");
            if (present == false || dacl == IntPtr.Zero)
                throw new InvalidOperationException("Default DACL is absent");
            DefaultDacl information = new DefaultDacl { Dacl = dacl };
            Require(SetTokenInformation(token, 6, ref information, (uint)Marshal.SizeOf(typeof(DefaultDacl))),
                "SetTokenInformation(TokenDefaultDacl)");
        } finally {
            if (descriptor != IntPtr.Zero && LocalFree(descriptor) != IntPtr.Zero)
                throw new Win32Exception(Marshal.GetLastWin32Error(), "LocalFree(default DACL)");
        }
    }

    private static string DeleteInPrimaryProcess(IntPtr token, string parent, string file) {
        string cmd = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "cmd.exe");
        StringBuilder command = new StringBuilder("\"" + cmd + "\" /d /c del /f /q \"" + file + "\"");
        StartupInfo startup = new StartupInfo { Size = (uint)Marshal.SizeOf(typeof(StartupInfo)) };
        ProcessInfo process;
        Require(CreateProcessAsUserW(token, cmd, command, IntPtr.Zero, IntPtr.Zero, false,
            0x400, IntPtr.Zero, parent, ref startup, out process), "CreateProcessAsUserW");
        bool exited = false;
        try {
            uint wait = WaitForSingleObject(process.Process, 5000);
            if (wait == 258) {
                Require(TerminateProcess(process.Process, 1), "TerminateProcess(timed out diagnostic child)");
                if (WaitForSingleObject(process.Process, 5000) != 0)
                    throw new InvalidOperationException("Diagnostic child did not terminate");
                exited = true;
                throw new TimeoutException("Diagnostic child exceeded 5000 milliseconds");
            }
            if (wait != 0)
                throw new Win32Exception(Marshal.GetLastWin32Error(), "WaitForSingleObject(child)");
            exited = true;
            uint exit;
            Require(GetExitCodeProcess(process.Process, out exit), "GetExitCodeProcess");
            return string.Format("primaryExit={0},primaryFileRemains={1}", exit, File.Exists(file));
        } finally {
            if (exited == false) {
                Require(TerminateProcess(process.Process, 1), "TerminateProcess(diagnostic cleanup)");
                if (WaitForSingleObject(process.Process, 5000) != 0)
                    throw new InvalidOperationException("Diagnostic cleanup could not reap child");
            }
            Require(CloseHandle(process.Thread), "CloseHandle(child thread)");
            Require(CloseHandle(process.Process), "CloseHandle(child process)");
        }
    }

    public static string Run(string logonSid, string parent, string file) {
        IntPtr original = IntPtr.Zero;
        IntPtr restricted = IntPtr.Zero;
        SidAndAttributes[] sids = new SidAndAttributes[3];
        bool impersonating = false;
        try {
            Require(OpenProcessToken(GetCurrentProcess(), 0x1AB, out original), "OpenProcessToken");
            string[] values = { "S-1-5-21-111111111-222222222-333333333-4444", logonSid, "S-1-1-0" };
            for (int index = 0; index < values.Length; index++) {
                sids[index].Attributes = 0;
                Require(ConvertStringSidToSidW(values[index], out sids[index].Sid), "ConvertStringSidToSidW");
            }
            Require(CreateRestrictedToken(original, 0xD, 0, IntPtr.Zero, 0, IntPtr.Zero,
                (uint)sids.Length, sids, out restricted), "CreateRestrictedToken");
            SetDefaultDacl(restricted, logonSid);
            Require(ImpersonateLoggedOnUser(restricted), "ImpersonateLoggedOnUser");
            impersonating = true;
            int read = OpenError(file, 0x80000000, false);
            int write = OpenError(file, 0x40000000, false);
            int deleteOpen = OpenError(file, 0x10000, false);
            int deleteChildOpen = OpenError(parent, 0x40, true);
            int deletion = DeleteFileW(file) ? 0 : Marshal.GetLastWin32Error();
            Require(RevertToSelf(), "RevertToSelf(before primary process)");
            impersonating = false;
            if (File.Exists(file) == false)
                throw new InvalidOperationException("Impersonation removed the fixture; primary-process comparison needs a fresh fixture");
            string primary = DeleteInPrimaryProcess(restricted, parent, file);
            return string.Format("read={0},write={1},deleteOpen={2},parentDeleteChildOpen={3},deleteFile={4},{5}",
                read, write, deleteOpen, deleteChildOpen, deletion, primary);
        } finally {
            if (impersonating) Require(RevertToSelf(), "RevertToSelf");
            if (restricted != IntPtr.Zero) Require(CloseHandle(restricted), "CloseHandle(restricted token)");
            if (original != IntPtr.Zero) Require(CloseHandle(original), "CloseHandle(original token)");
            foreach (SidAndAttributes sid in sids)
                if (sid.Sid != IntPtr.Zero && LocalFree(sid.Sid) != IntPtr.Zero)
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "LocalFree(SID)");
        }
    }
}
'@

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $logonSid = [RestrictedDeleteProbe]::LogonSid()
    $everyone = [Security.Principal.SecurityIdentifier]::new('S-1-1-0')
    Write-Output ('Restricted token probe identity: ' + $identity.User.Value)
    Write-Output 'Restricted token flags: DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED (0xD)'
    Write-Output 'Zero denotes API success; nonzero values are exact Win32 errors. This probe does not establish product confinement.'

    foreach ($mode in @('ambient', 'file-delete-denied', 'parent-delete-child-denied', 'both-denied')) {
        $parent = Join-Path $probeRoot $mode
        New-Item -ItemType Directory -Path $parent | Out-Null
        $file = Join-Path $parent 'outside.txt'
        [IO.File]::WriteAllText($file, 'outside')
        $originalParentAcl = Get-Acl -LiteralPath $parent
        $originalFileAcl = Get-Acl -LiteralPath $file

        try {
            if ($mode -eq 'file-delete-denied' -or $mode -eq 'both-denied') {
                $acl = Get-Acl -LiteralPath $file
                $rule = [Security.AccessControl.FileSystemAccessRule]::new($everyone,
                    [Security.AccessControl.FileSystemRights]::Delete, [Security.AccessControl.AccessControlType]::Deny)
                $acl.AddAccessRule($rule)
                Set-Acl -LiteralPath $file -AclObject $acl
            }
            if ($mode -eq 'parent-delete-child-denied' -or $mode -eq 'both-denied') {
                $acl = Get-Acl -LiteralPath $parent
                $rule = [Security.AccessControl.FileSystemAccessRule]::new($everyone,
                    [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles,
                    [Security.AccessControl.AccessControlType]::Deny)
                $acl.AddAccessRule($rule)
                Set-Acl -LiteralPath $parent -AclObject $acl
            }
            Write-Output ('Delete access observation: ' + $mode + ': ' +
                [RestrictedDeleteProbe]::Run($logonSid, $parent, $file))
            Write-Output ('Fixture remains: ' + (Test-Path -LiteralPath $file))
        } finally {
            Set-Acl -LiteralPath $parent -AclObject $originalParentAcl
            if (Test-Path -LiteralPath $file) { Set-Acl -LiteralPath $file -AclObject $originalFileAcl }
        }
    }
} finally {
    Remove-Item -LiteralPath $probeRoot -Recurse -Force
    if (Test-Path -LiteralPath $probeRoot) { throw "Diagnostic cleanup failed: $probeRoot" }
}
