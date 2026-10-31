package top.tianyan.app.plugin.cli

import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val out = PrintStream(System.out, true, Charsets.UTF_8)
    val err = PrintStream(System.err, true, Charsets.UTF_8)
    exitProcess(Cli(out, err).run(args))
}
