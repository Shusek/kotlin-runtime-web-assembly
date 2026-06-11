package uk.shusek.krwa.wasi

/** WASI [rights](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#rights) */
class WasiRights private constructor() {
    companion object {
        const val FD_DATASYNC = 1
        const val FD_READ = 2
        const val FD_SEEK = 4
        const val FD_FDSTAT_SET_FLAGS = 8
        const val FD_SYNC = 16
        const val FD_TELL = 32
        const val FD_WRITE = 64
        const val FD_ADVISE = 128
        const val FD_ALLOCATE = 256
        const val PATH_CREATE_DIRECTORY = 512
        const val PATH_CREATE_FILE = 1024
        const val PATH_LINK_SOURCE = 2048
        const val PATH_LINK_TARGET = 4096
        const val PATH_OPEN = 8192
        const val FD_READDIR = 16384
        const val PATH_READLINK = 32768
        const val PATH_RENAME_SOURCE = 65536
        const val PATH_RENAME_TARGET = 131072
        const val PATH_FILESTAT_GET = 262144
        const val PATH_FILESTAT_SET_SIZE = 524288
        const val PATH_FILESTAT_SET_TIMES = 1048576
        const val FD_FILESTAT_GET = 2097152
        const val FD_FILESTAT_SET_SIZE = 4194304
        const val FD_FILESTAT_SET_TIMES = 8388608
        const val PATH_SYMLINK = 16777216
        const val PATH_REMOVE_DIRECTORY = 33554432
        const val PATH_UNLINK_FILE = 67108864
        const val POLL_FD_READWRITE = 134217728
        const val SOCK_SHUTDOWN = 268435456
        const val SOCK_ACCEPT = 536870912

        const val FILE_RIGHTS_BASE =
            FD_DATASYNC or
                FD_READ or
                FD_SEEK or
                FD_FDSTAT_SET_FLAGS or
                FD_SYNC or
                FD_TELL or
                FD_WRITE or
                FD_ADVISE or
                FD_ALLOCATE or
                FD_FILESTAT_GET or
                FD_FILESTAT_SET_SIZE or
                FD_FILESTAT_SET_TIMES or
                POLL_FD_READWRITE

        const val DIRECTORY_RIGHTS_BASE =
            FD_DATASYNC or
                FD_FDSTAT_SET_FLAGS or
                FD_SYNC or
                PATH_CREATE_DIRECTORY or
                PATH_CREATE_FILE or
                PATH_LINK_SOURCE or
                PATH_LINK_TARGET or
                PATH_OPEN or
                FD_READDIR or
                PATH_READLINK or
                PATH_RENAME_SOURCE or
                PATH_RENAME_TARGET or
                PATH_FILESTAT_GET or
                PATH_FILESTAT_SET_SIZE or
                PATH_FILESTAT_SET_TIMES or
                FD_FILESTAT_GET or
                FD_FILESTAT_SET_TIMES or
                PATH_SYMLINK or
                PATH_REMOVE_DIRECTORY or
                PATH_UNLINK_FILE
    }
}
