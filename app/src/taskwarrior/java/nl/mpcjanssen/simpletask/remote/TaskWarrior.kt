package nl.mpcjanssen.simpletask.remote

import android.os.Build
import nl.mpcjanssen.simpletask.Logger
import nl.mpcjanssen.simpletask.R

import nl.mpcjanssen.simpletask.TodoApplication
import org.json.JSONObject
import android.text.TextUtils
import java.io.*
import java.util.Collections.addAll


interface StreamConsumer {
    fun eat(line: String?)
}

private class ToLogConsumer(private val level: String, private val tag: String) : StreamConsumer {
    override fun eat(line: String?) {
        line?.let {
            when (level) {
                "error" -> Logger.error(tag, line)
                "debug" -> Logger.debug(tag, line)
                "warning" -> Logger.warn(tag, line)
                "info" -> Logger.info(tag, line)
                else -> Logger.warn(tag, line)
            }
        }
    }
}

private val errConsumer = ToLogConsumer("warning", "TaskWarrior")
private val outConsumer = ToLogConsumer("info", "TaskWarrior")

object TaskWarrior {
    val TAG = "TaskWarrior"
    private enum class Arch {
        Arm7, X86
    };

    val executable : String? by lazy {
        eabiExecutable()
    }

    private fun eabiExecutable(): String? {
        var arch = Arch.Arm7
        val eabi = Build.CPU_ABI
        if (eabi == "x86" || eabi == "x86_64") {
            arch = Arch.X86
        }
        var rawID = -1
        when (arch) {
            Arch.Arm7 -> rawID = if (Build.VERSION.SDK_INT >= 16) R.raw.task_arm7_16 else R.raw.task_arm7
            Arch.X86 -> rawID = if (Build.VERSION.SDK_INT >= 16) R.raw.task_x86_16 else R.raw.task_x86
        }
        try {
            val file = File(TodoApplication.app.getFilesDir(), "task")
            if (!file.exists()) {
                val rawStream = TodoApplication.app.getResources().openRawResource(rawID)
                val outputStream = FileOutputStream(file)
                rawStream.copyTo(outputStream, 8912)
                outputStream.close()
                rawStream.close()
            }
            file.setExecutable(true, true)
            return file.getAbsolutePath()
        } catch (e: IOException) {
            Logger.error(TAG, "Error preparing file", e)
        }
        return null
    }

    fun taskList(): List<String> {
        val result = ArrayList<JSONObject>()
        val params = ArrayList<String>()
        params.add("rc.json.array=off")
        params.add("export")
        callTask(object : StreamConsumer {
            override fun eat(line: String?) {
                if (!TextUtils.isEmpty(line)) {
                    try {
                        result.add(JSONObject(line))
                    } catch (e: Exception) {
                        Logger.error(TAG, "Not JSON object: ${line}" ,e )
                    }

                }
            }
        }, errConsumer, *params.toTypedArray())
        Logger.debug(TAG, "List for size  ${result.size}")
        return result.map {jsonToTodotxt(it)}
    }

    private val  UUID = "uuid"
    private val  DESC = "description"

    fun jsonToTodotxt (json: JSONObject) : String {
            val uuid = json.getString(UUID)
            val desc = json.getString(DESC)
            return "$desc $UUID:$uuid"
    }

    fun callTask(out: StreamConsumer, err: StreamConsumer, vararg arguments: String): Boolean {
        val result = callTask(out, err, true, *arguments)
        return result == 0
    }

    private val tasksFolder = File("/sdcard/TW")

    private fun callTask(out: StreamConsumer, err: StreamConsumer, api: Boolean, vararg arguments: String): Int {
        try {
            val exec = executable
            if (null == exec) {
                Logger.debug(TAG, "Error in binary call: executable not found")
                throw RuntimeException("Invalid executable")
            }
            if (null == tasksFolder) {
                Logger.debug(TAG, "Error in binary call: invalid profile folder")
                throw RuntimeException("Invalid folder")
            }
            val args = ArrayList<String>()
            args.add(exec)
            args.add("rc.color=off")
            if (api) {
                args.add("rc.confirmation=off")
                args.add("rc.verbose=nothing")
            } else {
                args.add("rc.verbose=none")
            }
            args.addAll(arguments)
            val pb = ProcessBuilder(args)
            pb.directory(tasksFolder)
            pb.environment().put("TASKRC", File(tasksFolder, ".taskrc").getAbsolutePath())
            pb.environment().put("TASKDATA", File(tasksFolder, "data").getAbsolutePath())
            val p = pb.start()
            Logger.debug(TAG,"Calling now: ${tasksFolder}  ${args}")
            //            debug("Execute:", args);
            val outThread = readStream(p.getInputStream(), out)
            val errThread = readStream(p.getErrorStream(), err)
            val exitCode = p.waitFor()
            Logger.debug(TAG, "Exit code:  $exitCode, $args")
            //            debug("Execute result:", exitCode);
            if (null != outThread) outThread!!.join()
            if (null != errThread) errThread!!.join()
            return exitCode
        } catch (e: Exception) {
            Logger.error(TAG,"Failed to execute task", e)
            err.eat(e.message)
            return 255
        }
    }

    private fun readStream(stream: InputStream,  consumer: StreamConsumer): Thread? {
        val reader: Reader
        try {
            reader = InputStreamReader(stream, "utf-8")
        } catch (e: UnsupportedEncodingException) {
            Logger.error(TAG,"Error opening stream")
            return null
        }

        val thread = object : Thread() {
            override fun run() {
                stream.bufferedReader().lineSequence().forEach {
                    consumer.eat(it)
                }
            }
        }
        thread.start()
        return thread
    }
}