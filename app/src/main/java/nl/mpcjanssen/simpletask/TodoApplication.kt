/**

 * Copyright (c) 2009-2012 Todo.txt contributors (http://todotxt.com)
 * Copyright (c) 2013- Mark Janssen
 * Copyright (c) 2015 Vojtech Kral

 * LICENSE:

 * Simpletas is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.

 * Simpletask is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.

 * You should have received a copy of the GNU General Public License along with Sinpletask.  If not, see
 * //www.gnu.org/licenses/>.

 * @author Todo.txt contributors @yahoogroups.com>
 * *
 * @license http://www.gnu.org/licenses/gpl.html
 * *
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 * *
 * @copyright 2013- Mark Janssen
 * *
 * @copyright 2015 Vojtech Kral
 */
package nl.mpcjanssen.simpletask

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import nl.mpcjanssen.simpletask.remote.*
import nl.mpcjanssen.simpletask.task.TodoList
import nl.mpcjanssen.simpletask.util.Config
import nl.mpcjanssen.simpletask.util.appVersion
import nl.mpcjanssen.simpletask.util.todayAsString
import java.util.*

class TodoApplication : Application(), FileSelectedListener {

    lateinit private var androidUncaughtExceptionHandler: Thread.UncaughtExceptionHandler
    lateinit var localBroadCastManager: LocalBroadcastManager

    override fun onCreate() {
        app = this
        super.onCreate()

        localBroadCastManager = LocalBroadcastManager.getInstance(this)

        setupUncaughtExceptionHandler()


        Log.i(TAG, "Created todolist " + TodoList)
        Log.i(TAG, "onCreate()")
        Log.i(TAG, "Started ${appVersion(this)}")
        scheduleOnNewDay()
        loadTodoList("Initial load")
    }

    private fun setupUncaughtExceptionHandler() {
        // save original Uncaught exception handler
        androidUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        // Handle all uncaught exceptions for logging.
        // After that call the default uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            androidUncaughtExceptionHandler.uncaughtException(thread, throwable)
        }
    }

    private fun scheduleOnNewDay() {
        // Schedules activities to run on a new day
        // - Refresh widgets and UI
        // - Cleans up logging

        val calendar = Calendar.getInstance()

        // Prevent alarm from triggering for today when setting it
        calendar.add(Calendar.DATE, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 2)
        calendar.set(Calendar.SECOND, 0)

        Log.i(TAG, "Scheduling daily UI updateCache alarm, first at ${calendar.time}")
        val pi = PendingIntent.getBroadcast(this, 0,
                Intent(this, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        val am = this.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setRepeating(AlarmManager.RTC_WAKEUP, calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY, pi)
    }

    fun switchTodoFile(newTodo: String) {
        Config.setRcFile(newTodo)
        loadTodoList("from file switch")
    }

    fun loadTodoList(reason: String) {
        Log.v(TAG, "Reloading file: $reason")
        TodoList.reload()
    }

    override fun fileSelected(file: String) {
        Config.setRcFile(file)
        loadTodoList("from fileChanged")
    }

    fun browseForNewFile(act: Activity) {
        FileDialog.browseForNewFile(
                act,
                Config.rcFile.parent,
                object : FileSelectedListener {
                    override fun fileSelected(file: String) {
                        switchTodoFile(file)
                    }
                })
    }

    fun getSortString(key: String): String {
        val keys = Arrays.asList(*resources.getStringArray(R.array.sortKeys))
        val values = resources.getStringArray(R.array.sort)
        val index = keys.indexOf(key)
        if (index == -1) {
            return getString(R.string.none)
        }
        return values[index]
    }

    companion object {
        private val TAG = TodoApplication::class.java.simpleName
        fun atLeastAPI(api: Int): Boolean = android.os.Build.VERSION.SDK_INT >= api
        lateinit var app : TodoApplication
    }

    var today: String = todayAsString
}

