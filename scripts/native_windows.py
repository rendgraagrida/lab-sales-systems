"""Win32 job-owned processes and named manual-reset events for the local launcher."""

import ctypes
from contextlib import ExitStack
from ctypes import wintypes
import math
import os
from pathlib import Path
import socket
import subprocess
import threading
import time


if os.name == 'nt':
    _kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    _HANDLE = wintypes.HANDLE
    _DWORD = wintypes.DWORD
    _BOOL = wintypes.BOOL
    _SIZE_T = ctypes.c_size_t
    _INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

    class _SECURITY_ATTRIBUTES(ctypes.Structure):
        _fields_ = [('nLength', _DWORD), ('lpSecurityDescriptor', ctypes.c_void_p),
                    ('bInheritHandle', _BOOL)]

    class _IO_COUNTERS(ctypes.Structure):
        _fields_ = [(name, ctypes.c_ulonglong) for name in (
            'ReadOperationCount', 'WriteOperationCount', 'OtherOperationCount',
            'ReadTransferCount', 'WriteTransferCount', 'OtherTransferCount')]

    class _BASIC_LIMIT_INFORMATION(ctypes.Structure):
        _fields_ = [('PerProcessUserTimeLimit', ctypes.c_longlong),
                    ('PerJobUserTimeLimit', ctypes.c_longlong), ('LimitFlags', _DWORD),
                    ('MinimumWorkingSetSize', _SIZE_T), ('MaximumWorkingSetSize', _SIZE_T),
                    ('ActiveProcessLimit', _DWORD), ('Affinity', _SIZE_T),
                    ('PriorityClass', _DWORD), ('SchedulingClass', _DWORD)]

    class _EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
        _fields_ = [('BasicLimitInformation', _BASIC_LIMIT_INFORMATION),
                    ('IoInfo', _IO_COUNTERS), ('ProcessMemoryLimit', _SIZE_T),
                    ('JobMemoryLimit', _SIZE_T), ('PeakProcessMemoryUsed', _SIZE_T),
                    ('PeakJobMemoryUsed', _SIZE_T)]

    class _BASIC_ACCOUNTING_INFORMATION(ctypes.Structure):
        _fields_ = [('TotalUserTime', ctypes.c_longlong), ('TotalKernelTime', ctypes.c_longlong),
                    ('ThisPeriodTotalUserTime', ctypes.c_longlong),
                    ('ThisPeriodTotalKernelTime', ctypes.c_longlong),
                    ('TotalPageFaultCount', _DWORD), ('TotalProcesses', _DWORD),
                    ('ActiveProcesses', _DWORD), ('TotalTerminatedProcesses', _DWORD)]

    class _STARTUPINFO(ctypes.Structure):
        _fields_ = [('cb', _DWORD), ('lpReserved', wintypes.LPWSTR),
                    ('lpDesktop', wintypes.LPWSTR), ('lpTitle', wintypes.LPWSTR),
                    ('dwX', _DWORD), ('dwY', _DWORD), ('dwXSize', _DWORD),
                    ('dwYSize', _DWORD), ('dwXCountChars', _DWORD), ('dwYCountChars', _DWORD),
                    ('dwFillAttribute', _DWORD), ('dwFlags', _DWORD),
                    ('wShowWindow', wintypes.WORD), ('cbReserved2', wintypes.WORD),
                    ('lpReserved2', ctypes.POINTER(ctypes.c_byte)),
                    ('hStdInput', _HANDLE), ('hStdOutput', _HANDLE), ('hStdError', _HANDLE)]

    class _STARTUPINFOEX(ctypes.Structure):
        _fields_ = [('StartupInfo', _STARTUPINFO), ('lpAttributeList', ctypes.c_void_p)]

    class _PROCESS_INFORMATION(ctypes.Structure):
        _fields_ = [('hProcess', _HANDLE), ('hThread', _HANDLE),
                    ('dwProcessId', _DWORD), ('dwThreadId', _DWORD)]

    def _api(name, restype, *argtypes):
        function = getattr(_kernel, name)
        function.restype = restype
        function.argtypes = argtypes
        return function

    _create_job = _api('CreateJobObjectW', _HANDLE, ctypes.c_void_p, wintypes.LPCWSTR)
    _set_job = _api('SetInformationJobObject', _BOOL, _HANDLE, ctypes.c_int, ctypes.c_void_p, _DWORD)
    _query_job = _api('QueryInformationJobObject', _BOOL, _HANDLE, ctypes.c_int,
                      ctypes.c_void_p, _DWORD, ctypes.POINTER(_DWORD))
    _terminate_job = _api('TerminateJobObject', _BOOL, _HANDLE, wintypes.UINT)
    _close_handle = _api('CloseHandle', _BOOL, _HANDLE)
    _create_file = _api('CreateFileW', _HANDLE, wintypes.LPCWSTR, _DWORD, _DWORD,
                        ctypes.POINTER(_SECURITY_ATTRIBUTES), _DWORD, _DWORD, _HANDLE)
    _init_attributes = _api('InitializeProcThreadAttributeList', _BOOL, ctypes.c_void_p,
                            _DWORD, _DWORD, ctypes.POINTER(_SIZE_T))
    _update_attributes = _api('UpdateProcThreadAttribute', _BOOL, ctypes.c_void_p, _DWORD,
                              _SIZE_T, ctypes.c_void_p, _SIZE_T, ctypes.c_void_p,
                              ctypes.POINTER(_SIZE_T))
    _delete_attributes = _api('DeleteProcThreadAttributeList', None, ctypes.c_void_p)
    _create_process = _api('CreateProcessW', _BOOL, wintypes.LPCWSTR, wintypes.LPWSTR,
                           ctypes.c_void_p, ctypes.c_void_p, _BOOL, _DWORD, ctypes.c_void_p,
                           wintypes.LPCWSTR, ctypes.POINTER(_STARTUPINFO),
                           ctypes.POINTER(_PROCESS_INFORMATION))
    _resume_thread = _api('ResumeThread', _DWORD, _HANDLE)
    _terminate_process = _api('TerminateProcess', _BOOL, _HANDLE, wintypes.UINT)
    _wait = _api('WaitForSingleObject', _DWORD, _HANDLE, _DWORD)
    _exit_code = _api('GetExitCodeProcess', _BOOL, _HANDLE, ctypes.POINTER(_DWORD))
    _create_event = _api('CreateEventW', _HANDLE, ctypes.c_void_p, _BOOL, _BOOL, wintypes.LPCWSTR)
    _open_event = _api('OpenEventW', _HANDLE, _DWORD, _BOOL, wintypes.LPCWSTR)
    _set_event = _api('SetEvent', _BOOL, _HANDLE)
    _ip_helper = ctypes.WinDLL('iphlpapi', use_last_error=True)
    _tcp_table = _ip_helper.GetExtendedTcpTable
    _tcp_table.restype = _DWORD
    _tcp_table.argtypes = [ctypes.c_void_p, ctypes.POINTER(_DWORD), _BOOL, _DWORD, _DWORD, _DWORD]

    class _TCP_OWNER_ROW(ctypes.Structure):
        _fields_ = [(name, _DWORD) for name in (
            'state', 'local_address', 'local_port', 'remote_address', 'remote_port', 'pid')]


def _require_windows():
    if os.name != 'nt':
        raise OSError('native Windows process ownership requires Windows')


def _error():
    return ctypes.WinError(ctypes.get_last_error())


def _close(handle):
    if handle and not _close_handle(handle):
        raise _error()


def _timeout_deadline(timeout):
    value = float(timeout)
    if math.isnan(value):
        raise ValueError('timeout must not be NaN')
    return time.monotonic() + max(0.0, value)


def _wait_until(handle, deadline):
    while True:
        remaining = max(0.0, deadline - time.monotonic())
        milliseconds = math.ceil(min(remaining, 0xfffffffe / 1000) * 1000)
        result = _wait(handle, milliseconds)
        if result == 0:
            return True
        if result != 0x102:
            raise _error()
        if time.monotonic() >= deadline:
            return False


def _environment_block(env):
    entries = []
    seen = set()
    for key, value in env.items():
        if not isinstance(key, str) or not isinstance(value, str):
            raise TypeError('environment keys and values must be strings')
        if not key or '=' in key or '\0' in key or '\0' in value:
            raise ValueError('invalid environment entry')
        folded = key.upper()
        if folded in seen:
            raise ValueError('duplicate Windows environment key')
        seen.add(folded)
        entries.append((folded, key + '=' + value))
    entries.sort(key=lambda entry: entry[0])
    return ctypes.create_unicode_buffer('\0'.join(entry[1] for entry in entries) + '\0\0')


class NativeProcess:
    def __init__(self, handle, pid):
        self._handle = handle
        self.pid = pid

    def poll(self):
        if not self._handle:
            raise OSError('process handle is closed')
        result = _wait(self._handle, 0)
        if result == 0x102:
            return None
        if result != 0:
            raise _error()
        code = _DWORD()
        if not _exit_code(self._handle, ctypes.byref(code)):
            raise _error()
        return code.value

    def wait(self, timeout):
        if not self._handle:
            raise OSError('process handle is closed')
        if not _wait_until(self._handle, _timeout_deadline(timeout)):
            raise subprocess.TimeoutExpired('native process', timeout)
        return self.poll()

    def close(self):
        if self._handle:
            handle, self._handle = self._handle, None
            _close(handle)


class NativeJob:
    def __init__(self):
        _require_windows()
        self._lock = threading.RLock()
        self._handle = _create_job(None, None)
        if not self._handle:
            raise _error()
        limits = _EXTENDED_LIMIT_INFORMATION()
        limits.BasicLimitInformation.LimitFlags = 0x2000  # KILL_ON_JOB_CLOSE
        if not _set_job(self._handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = _error()
            _close(self._handle)
            self._handle = None
            raise error

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def spawn(self, argv: list[str], env: dict[str, str], cwd: Path, log_path: Path) -> NativeProcess:
        if not argv or any(not isinstance(arg, str) or '\0' in arg for arg in argv) or not argv[0]:
            raise ValueError('argv must contain a nonempty executable and valid string arguments')
        command = ctypes.create_unicode_buffer(subprocess.list2cmdline(argv))
        environment = _environment_block(env)
        with self._lock:
            if not self._handle:
                raise OSError('job handle is closed')
            return self._spawn(argv[0], command, environment, cwd, log_path)

    def _spawn(self, executable, command, environment, cwd, log_path):
        security = _SECURITY_ATTRIBUTES(ctypes.sizeof(_SECURITY_ATTRIBUTES), None, True)
        process = _PROCESS_INFORMATION()
        resumed = False
        try:
            with ExitStack() as cleanup:
                log = _create_file(str(log_path), 0x4, 0x7, ctypes.byref(security), 4, 0x80, None)
                if log == _INVALID_HANDLE_VALUE:
                    raise _error()
                cleanup.callback(_close, log)
                stdin = _create_file('NUL', 0x80000000, 0x3, ctypes.byref(security), 3, 0x80, None)
                if stdin == _INVALID_HANDLE_VALUE:
                    raise _error()
                cleanup.callback(_close, stdin)
                size = _SIZE_T()
                _init_attributes(None, 2, 0, ctypes.byref(size))
                if not size.value:
                    raise _error()
                buffer = ctypes.create_string_buffer(size.value)
                attributes = ctypes.cast(buffer, ctypes.c_void_p)
                if not _init_attributes(attributes, 2, 0, ctypes.byref(size)):
                    raise _error()
                cleanup.callback(_delete_attributes, attributes)
                # Only these inheritable handles cross CreateProcess; the job handle never does.
                handles = (_HANDLE * 2)(stdin, log)
                if not _update_attributes(attributes, 0, 0x20002, ctypes.cast(handles, ctypes.c_void_p),
                                          ctypes.sizeof(handles), None, None):
                    raise _error()
                # Atomic job assignment eliminates the suspended-child crash window.
                jobs = (_HANDLE * 1)(self._handle)
                if not _update_attributes(attributes, 0, 0x2000d, ctypes.cast(jobs, ctypes.c_void_p),
                                          ctypes.sizeof(jobs), None, None):
                    raise _error()
                startup = _STARTUPINFOEX()
                startup.StartupInfo.cb = ctypes.sizeof(startup)
                startup.StartupInfo.dwFlags = 0x100
                startup.StartupInfo.hStdInput = stdin
                startup.StartupInfo.hStdOutput = log
                startup.StartupInfo.hStdError = log
                startup.lpAttributeList = attributes
                flags = 0x4 | 0x400 | 0x80000  # SUSPENDED, UNICODE_ENVIRONMENT, EXTENDED_STARTUPINFO
                if not _create_process(executable, command, None, None, True, flags,
                                       ctypes.cast(environment, ctypes.c_void_p), str(cwd),
                                       ctypes.byref(startup.StartupInfo), ctypes.byref(process)):
                    raise _error()
                cleanup.callback(_close, process.hThread)
                if _resume_thread(process.hThread) == 0xffffffff:
                    raise _error()
                resumed = True
            return NativeProcess(process.hProcess, process.dwProcessId)
        except BaseException:
            if process.hProcess:
                # A failed handoff must not leave the child or its descendants alive.
                if resumed:
                    _terminate_job(self._handle, 1)
                if _terminate_process(process.hProcess, 1):
                    _wait(process.hProcess, 0xffffffff)
                _close(process.hProcess)
            raise

    def close(self):
        with self._lock:
            if not self._handle:
                return
            handle, self._handle = self._handle, None
            try:
                if not _terminate_job(handle, 1):
                    raise _error()
                accounting = _BASIC_ACCOUNTING_INFORMATION()
                while True:
                    if not _query_job(handle, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None):
                        raise _error()
                    if not accounting.ActiveProcesses:
                        break
                    time.sleep(0.01)
            finally:
                _close(handle)


class WindowsEvent:
    def __init__(self, name: str, create: bool = False):
        _require_windows()
        if not isinstance(name, str) or not name or '\0' in name:
            raise ValueError('event name must be a nonempty string without NUL')
        if create:
            ctypes.set_last_error(0)
            handle = _create_event(None, True, False, name)
            if not handle:
                raise _error()
            if ctypes.get_last_error() == 183:
                _close(handle)
                raise FileExistsError(183, 'named event already exists', name)
        else:
            handle = _open_event(0x100002, False, name)  # SYNCHRONIZE | EVENT_MODIFY_STATE
            if not handle:
                raise _error()
        self._handle = handle

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def set(self):
        if not self._handle:
            raise OSError('event handle is closed')
        if not _set_event(self._handle):
            raise _error()

    def wait(self, timeout: float) -> bool:
        if not self._handle:
            raise OSError('event handle is closed')
        return _wait_until(self._handle, _timeout_deadline(timeout))

    def close(self):
        if self._handle:
            handle, self._handle = self._handle, None
            _close(handle)


def owns_loopback_listener(pid: int, port: int) -> bool:
    """Check actual IPv4 TCP listener ownership; a shared revision is not instance identity."""
    _require_windows()
    size = _DWORD()
    if _tcp_table(None, ctypes.byref(size), False, 2, 3, 0) not in (0, 122):
        raise _error()
    if not 4 <= size.value <= 16 * 1024 * 1024:
        raise OSError('unexpected native TCP table size')
    table = ctypes.create_string_buffer(size.value)
    result = _tcp_table(table, ctypes.byref(size), False, 2, 3, 0)
    if result == 122:
        return False  # Concurrent table growth cannot establish ownership.
    if result:
        raise OSError(result, 'native TCP owner lookup failed')
    count = _DWORD.from_buffer(table).value
    row_size = ctypes.sizeof(_TCP_OWNER_ROW)
    if 4 + count * row_size > len(table):
        raise OSError('invalid native TCP owner table')
    loopback = int.from_bytes(socket.inet_aton('127.0.0.1'), 'little')
    for index in range(count):
        row = _TCP_OWNER_ROW.from_buffer(table, 4 + index * row_size)
        if (row.state == 2 and row.local_address == loopback
                and socket.ntohs(row.local_port & 0xffff) == port and row.pid == pid):
            return True
    return False
