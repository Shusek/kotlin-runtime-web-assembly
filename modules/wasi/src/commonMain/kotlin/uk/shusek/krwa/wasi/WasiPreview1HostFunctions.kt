package uk.shusek.krwa.wasi

import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

object WasiPreview1HostFunctions {
    fun toHostFunctions(functions: WasiPreview1Host): Array<HostFunction> =
        toHostFunctions(functions, "wasi_snapshot_preview1")

    fun toHostFunctions(functions: WasiPreview1Host, moduleName: String): Array<HostFunction> =
        arrayOf(
            HostFunction(
                moduleName,
                "adapter_close_badfd",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.adapterCloseBadfd(args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "adapter_open_badfd",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.adapterOpenBadfd(args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "args_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.argsGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "args_sizes_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.argsSizesGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "clock_res_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.clockResGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "clock_time_get",
                FunctionType.of(listOf(ValType.I32, ValType.I64, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.clockTimeGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++], args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "environ_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.environGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "environ_sizes_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.environSizesGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_advise",
                FunctionType.of(listOf(ValType.I32, ValType.I64, ValType.I64, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdAdvise(args[argIdx++].toInt(), args[argIdx++], args[argIdx++], args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_allocate",
                FunctionType.of(listOf(ValType.I32, ValType.I64, ValType.I64), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdAllocate(args[argIdx++].toInt(), args[argIdx++], args[argIdx++])
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_close",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdClose(args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_datasync",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdDatasync(args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_fdstat_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdFdstatGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_fdstat_set_flags",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdFdstatSetFlags(args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_fdstat_set_rights",
                FunctionType.of(listOf(ValType.I32, ValType.I64, ValType.I64), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdFdstatSetRights(args[argIdx++].toInt(), args[argIdx++], args[argIdx++])
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_filestat_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdFilestatGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_filestat_set_size",
                FunctionType.of(listOf(ValType.I32, ValType.I64), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdFilestatSetSize(args[argIdx++].toInt(), args[argIdx++])
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_filestat_set_times",
                FunctionType.of(listOf(ValType.I32, ValType.I64, ValType.I64, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdFilestatSetTimes(args[argIdx++].toInt(), args[argIdx++], args[argIdx++], args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_pread",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I64, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdPread(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++], args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_prestat_dir_name",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdPrestatDirName(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_prestat_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdPrestatGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_pwrite",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I64, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdPwrite(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++], args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_read",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdRead(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_readdir",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I64, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdReaddir(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++], args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_renumber",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdRenumber(args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_seek",
                FunctionType.of(listOf(ValType.I32, ValType.I64, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdSeek(instance.memory(), args[argIdx++].toInt(), args[argIdx++], args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_sync",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdSync(args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_tell",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdTell(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "fd_write",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.fdWrite(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_create_directory",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathCreateDirectory(args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()))
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_filestat_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathFilestatGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_filestat_set_times",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I64, ValType.I64, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathFilestatSetTimes(args[argIdx++].toInt(), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()), args[argIdx++], args[argIdx++], args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_link",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathLink(args[argIdx++].toInt(), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()))
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_open",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I64, ValType.I64, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathOpen(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()), args[argIdx++].toInt(), args[argIdx++], args[argIdx++], args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_readlink",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathReadlink(instance.memory(), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_remove_directory",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathRemoveDirectory(args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()))
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_rename",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathRename(args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()))
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_symlink",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathSymlink(instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()), args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()))
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "path_unlink_file",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pathUnlinkFile(args[argIdx++].toInt(), instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt()))
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "poll_oneoff",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.pollOneoff(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "proc_exit",
                FunctionType.of(listOf(ValType.I32), emptyList()),
            ) { instance, args ->
                var argIdx = 0
                functions.procExit(args[argIdx++].toInt())
                null
            },
            HostFunction(
                moduleName,
                "proc_raise",
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.procRaise(args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "random_get",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.randomGet(instance.memory(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "sched_yield",
                FunctionType.of(emptyList(), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.schedYield()
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "sock_accept",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.sockAccept(args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "sock_recv",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.sockRecv(args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "sock_send",
                FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.sockSend(args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            },
            HostFunction(
                moduleName,
                "sock_shutdown",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
            ) { instance, args ->
                var argIdx = 0
                val result = functions.sockShutdown(args[argIdx++].toInt(), args[argIdx++].toInt())
                longArrayOf(result.toLong())
            }
        )
}
