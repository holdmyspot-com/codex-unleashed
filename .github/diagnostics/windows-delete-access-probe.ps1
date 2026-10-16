$ErrorActionPreference = 'Stop'
$probeRoot = Join-Path $env:RUNNER_TEMP ('restricted-delete-probe-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $probeRoot | Out-Null

try {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class RestrictedDeleteProbe {
    [StructLayout(LayoutKind.Sequential)]
    private struct SidAndAttributes { public IntPtr Sid; public uint Attributes; }

    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError=true)] private static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    [DllImport("advapi32.dll", SetLastError=true)]
    private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
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
        if (!result) throw new Win32Exception(Marshal.GetLastWin32Error(), operation);
    }

    private static int OpenError(string path, uint access, bool directory) {
        IntPtr handle = CreateFileW(path, access, 7, IntPtr.Zero, 3,
            directory ? 0x02000000u : 0x80u, IntPtr.Zero);
        if (handle == new IntPtr(-1)) return Marshal.GetLastWin32Error();
        Require(CloseHandle(handle), "CloseHandle(file)");
        return 0;
    }

    public static string Run(string logonSid, string parent, string file) {
        IntPtr original = IntPtr.Zero;
        IntPtr restricted = IntPtr.Zero;
        SidAndAttributes[] sids = new SidAndAttributes[3];
        bool impersonating = false;
        try {
            Require(OpenProcessToken(GetCurrentProcess(), 0xE, out original), "OpenProcessToken");
            string[] values = { "S-1-15-3-1024-111-222-333-444", logonSid, "S-1-1-0" };
            for (int index = 0; index < values.Length; index++) {
                sids[index].Attributes = 0;
                Require(ConvertStringSidToSidW(values[index], out sids[index].Sid), "ConvertStringSidToSidW");
            }
            Require(CreateRestrictedToken(original, 0xD, 0, IntPtr.Zero, 0, IntPtr.Zero,
                (uint)sids.Length, sids, out restricted), "CreateRestrictedToken");
            Require(ImpersonateLoggedOnUser(restricted), "ImpersonateLoggedOnUser");
            impersonating = true;
            int read = OpenError(file, 0x80000000, false);
            int write = OpenError(file, 0x40000000, false);
            int deleteOpen = OpenError(file, 0x10000, false);
            int deleteChildOpen = OpenError(parent, 0x40, true);
            int deletion = DeleteFileW(file) ? 0 : Marshal.GetLastWin32Error();
            return string.Format("read={0},write={1},deleteOpen={2},parentDeleteChildOpen={3},deleteFile={4}",
                read, write, deleteOpen, deleteChildOpen, deletion);
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
    $logonSids = @($identity.Groups | Where-Object { $_.Value.StartsWith('S-1-5-5-') })
    if ($logonSids.Count -ne 1) { throw "Expected one logon SID; found $($logonSids.Count)" }
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
                [RestrictedDeleteProbe]::Run($logonSids[0].Value, $parent, $file))
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
