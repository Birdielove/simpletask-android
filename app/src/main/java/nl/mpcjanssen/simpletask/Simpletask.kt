/**
 * @author Mark Janssen
 * @author Vojtech Kral
 * *
 * @license http://www.gnu.org/licenses/gpl.html
 * *
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 * @copyright 2013- Mark Janssen
 * @copyright 2015 Vojtech Kral
 */

package nl.mpcjanssen.simpletask

import android.annotation.SuppressLint

import android.app.DatePickerDialog
import android.app.ProgressDialog
import android.app.SearchManager
import android.content.*
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.support.v4.content.ContextCompat
import android.support.v4.content.LocalBroadcastManager
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.AdapterView.OnItemLongClickListener
import hirondelle.date4j.DateTime
import kotlinx.android.synthetic.main.list_item.view.*
import kotlinx.android.synthetic.main.main.*
import kotlinx.android.synthetic.main.update_project_dialog.view.*
import kotlinx.android.synthetic.main.update_tags_dialog.view.*
import nl.mpcjanssen.simpletask.adapters.DrawerAdapter
import nl.mpcjanssen.simpletask.adapters.ItemDialogAdapter
import nl.mpcjanssen.simpletask.remote.TaskWarrior
import nl.mpcjanssen.simpletask.task.Task
import nl.mpcjanssen.simpletask.task.TaskList
import nl.mpcjanssen.simpletask.task.asCliList
import nl.mpcjanssen.simpletask.util.*
import org.jetbrains.anko.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import android.R.id as androidId

class Simpletask : ThemedNoActionBarActivity() {

    enum class Mode {
        NAV_DRAWER, FILTER_DRAWER, SELECTION, MAIN
    }

    var textSize: Float = 14.0F

    internal var options_menu: Menu? = null
    internal lateinit var m_app: STWApplication

    internal var m_adapter: TaskAdapter? = null
    private var m_broadcastReceiver: BroadcastReceiver? = null
    private var localBroadcastManager: LocalBroadcastManager? = null

    // Drawer side
    private val NAV_DRAWER = GravityCompat.END
    private val FILTER_DRAWER = GravityCompat.START

    private var m_drawerToggle: ActionBarDrawerToggle? = null
    private var m_savedInstanceState: Bundle? = null
    internal var m_scrollPosition = 0


    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        m_app = application as STWApplication
        m_savedInstanceState = savedInstanceState

        val intentFilter = IntentFilter()

        intentFilter.addAction(Constants.BROADCAST_UPDATE_UI)
        intentFilter.addAction(Constants.BROADCAST_SYNC_START)
        intentFilter.addAction(Constants.BROADCAST_THEME_CHANGED)
        intentFilter.addAction(Constants.BROADCAST_DATEBAR_SIZE_CHANGED)
        intentFilter.addAction(Constants.BROADCAST_SYNC_DONE)
        intentFilter.addAction(Constants.BROADCAST_HIGHLIGHT_SELECTION)

        textSize = Config.tasklistTextSize ?: textSize
        Log.i(TAG, "Text size = $textSize")
        setContentView(R.layout.main)

        localBroadcastManager = m_app.localBroadCastManager

        m_broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, receivedIntent: Intent) {

                if (receivedIntent.action == Constants.BROADCAST_UPDATE_UI) {
                    Log.i(TAG, "Updating UI because of broadcast")
                    textSize = Config.tasklistTextSize ?: textSize
                    if (m_adapter == null) {
                        return
                    }
                    m_adapter!!.setFilteredTasks()
                    invalidateOptionsMenu()
                    updateDrawers()
                } else if (receivedIntent.action == Constants.BROADCAST_HIGHLIGHT_SELECTION) {
                    m_adapter?.notifyDataSetChanged()
                    invalidateOptionsMenu()
                } else if (receivedIntent.action == Constants.BROADCAST_SYNC_START) {
                    showProgress(true)
                } else if (receivedIntent.action == Constants.BROADCAST_SYNC_DONE) {
                    showProgress(false)
                } else if (receivedIntent.action == Constants.BROADCAST_THEME_CHANGED ||
                        receivedIntent.action == Constants.BROADCAST_DATEBAR_SIZE_CHANGED) {
                    recreate()
                }
            }
        }
        localBroadcastManager!!.registerReceiver(m_broadcastReceiver, intentFilter)

        setSupportActionBar(main_actionbar)

        // Replace drawables if the theme is dark
        if (Config.isDarkTheme) {
            actionbar_clear?.setImageResource(R.drawable.ic_close_white_24dp)
        }
        val versionCode = BuildConfig.VERSION_CODE
        if (Config.latestChangelogShown < versionCode) {
            showChangelogOverlay(this)
            Config.latestChangelogShown = versionCode
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSION -> longToast("Permission granted, try again.")
        }
    }

    private fun showHelp() {
        val i = Intent(this, HelpScreen::class.java)
        startActivity(i)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred.
        if (m_drawerToggle != null) {
            m_drawerToggle!!.syncState()
        }
    }

    private fun selectedTasksAsTodoTxt(): String {
        return TaskList.selection.asCliList()
    }

    private fun selectAllTasks() {
        TaskList.selectTasks(m_adapter!!.visibleTasks)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (m_drawerToggle != null) {
            m_drawerToggle!!.onConfigurationChanged(newConfig)
        }
    }

    private fun handleIntent() {


        // Set the list's click listener
        filter_drawer?.onItemClickListener = DrawerItemClickListener()

        if (drawer_layout != null) {
            m_drawerToggle = object : ActionBarDrawerToggle(this, /* host Activity */
                    drawer_layout, /* DrawerLayout object */
                    R.string.changelist, /* "open drawer" description */
                    R.string.app_label /* "close drawer" description */) {

                /**
                 * Called when a drawer has settled in a completely closed
                 * state.
                 */
                override fun onDrawerClosed(view: View?) {
                    invalidateOptionsMenu()
                }

                /** Called when a drawer has settled in a completely open state.  */
                override fun onDrawerOpened(drawerView: View?) {
                    invalidateOptionsMenu()
                }
            }

            // Set the drawer toggle as the DrawerListener
            val toggle = m_drawerToggle as ActionBarDrawerToggle
            drawer_layout?.removeDrawerListener(toggle)
            drawer_layout?.addDrawerListener(toggle)
            val actionBar = supportActionBar
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true)
                actionBar.setHomeButtonEnabled(true)
                m_drawerToggle!!.isDrawerIndicatorEnabled = true
            }
            m_drawerToggle!!.syncState()
        }

        // Show search or filter results

        val adapter = m_adapter ?: TaskAdapter(layoutInflater)
        m_adapter = adapter

        m_adapter!!.setFilteredTasks()

        listView?.layoutManager = LinearLayoutManager(this)

        listView?.adapter = this.m_adapter

        fab.setOnClickListener { startAddTaskActivity() }
        invalidateOptionsMenu()
        updateDrawers()

        // If we were started from the widget, select the pushed task
        // next scroll to the first selected item
        ActionQueue.add("Scroll selection", Runnable {
            if (intent.hasExtra(Constants.INTENT_SELECTED_TASK_LINE)) {
                val position = intent.getIntExtra(Constants.INTENT_SELECTED_TASK_LINE, -1)
                intent.removeExtra(Constants.INTENT_SELECTED_TASK_LINE)
                setIntent(intent)
                if (position > -1) {
                    val itemAtPosition = adapter.getTask(position)
                    itemAtPosition?.let {
                        TaskList.clearSelection()
                        TaskList.selectTask(itemAtPosition)
                    }
                }
            }
            val selection = TaskList.selection
            if (selection.isNotEmpty()) {
                val selectedTask = selection[0]
                m_scrollPosition = adapter.getPosition(selectedTask)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager!!.unregisterReceiver(m_broadcastReceiver)
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        handleIntent()
    }

    override fun onPause() {
        val manager = listView?.layoutManager as LinearLayoutManager?
        if (manager != null) {
            val position = manager.findFirstVisibleItemPosition()
            val firstItemView = manager.findViewByPosition(position)
            val offset = firstItemView?.top ?: 0
            Log.i(TAG, "Saving scroll offset $position, $offset")
            Config.lastScrollPosition = position
            Config.lastScrollOffset = offset
        }
        super.onPause()
    }

    @SuppressLint("Recycle")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Log.i(TAG, "Recreating options menu")
        this.options_menu = menu

        val inflater = menuInflater
        val toggle = m_drawerToggle ?: return super.onCreateOptionsMenu(menu)
        val actionBar = supportActionBar ?: return super.onCreateOptionsMenu(menu)

        when (activeMode()) {
            Mode.NAV_DRAWER -> {
                setTitle(R.string.report_prompt)
            }
            Mode.FILTER_DRAWER -> {
                inflater.inflate(R.menu.filter_drawer, menu)
                setTitle(R.string.title_filter_drawer)
            }
            Mode.SELECTION -> {
                val actionColor = ContextCompat.getDrawable(this, R.color.gray74)
                actionBar.setBackgroundDrawable(actionColor)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    window.statusBarColor = ContextCompat.getColor(this, R.color.gray87)
                }

                inflater.inflate(R.menu.task_context_actionbar, menu)
                title = "${TaskList.numSelected()}"
                toggle.isDrawerIndicatorEnabled = false
                fab.visibility = View.GONE
                toolbar.setOnMenuItemClickListener { item ->
                    onOptionsItemSelected(item)
                }
                toolbar.visibility = View.VISIBLE
                toolbar.menu.clear()
                inflater.inflate(R.menu.task_context, toolbar.menu)

                val cbItem = toolbar.menu.findItem(R.id.multicomplete_checkbox)
                val selectedTasks = TaskList.selection
                val initialCompleteTasks = ArrayList<Task>()
                val initialIncompleteTasks = ArrayList<Task>()
                var cbState: Boolean?
                cbState = selectedTasks.getOrNull(0)?.isCompleted

                selectedTasks.forEach {
                    if (it.isCompleted) {
                        initialCompleteTasks.add(it)
                        if (!(cbState ?: false)) { cbState = null }
                    } else {
                        initialIncompleteTasks.add(it)
                        if (cbState ?: true) { cbState = null }
                    }
                }
                when (cbState) {
                    null -> cbItem.setIcon(R.drawable.ic_indeterminate_check_box_white_24dp)
                    false -> cbItem.setIcon(R.drawable.ic_check_box_outline_blank_white_24dp)
                    true -> cbItem.setIcon(R.drawable.ic_check_box_white_24dp)
                }

                cbItem.setOnMenuItemClickListener { _ ->
                    Log.i(TAG, "Clicked on completion checkbox, state: $cbState")
                    when (cbState) {
                        false -> completeTasks(selectedTasks)
                        true -> uncompleteTasks(selectedTasks)
                      null -> {
                          val popup = PopupMenu(this, toolbar)
                          val menuInflater = popup.menuInflater
                          menuInflater.inflate(R.menu.completion_popup, popup.menu)
                          popup.show()
                          popup.setOnMenuItemClickListener popup@ { item ->
                              val menuId = item.itemId
                              when (menuId) {
                                  R.id.complete -> completeTasks(selectedTasks)
                                  R.id.uncomplete -> uncompleteTasks(selectedTasks)
                              }
                              return@popup true
                          }
                      }
                    }
                    return@setOnMenuItemClickListener true
                }

                selection_fab.visibility = View.INVISIBLE
            }

            Mode.MAIN -> {
                val a : TypedArray = obtainStyledAttributes(intArrayOf(R.attr.colorPrimary, R.attr.colorPrimaryDark))
                try {
                    val colorPrimary = ContextCompat.getDrawable(this, a.getResourceId(0, 0))
                    actionBar.setBackgroundDrawable(colorPrimary)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        window.statusBarColor = ContextCompat.getColor(this, a.getResourceId(1, 0))
                    }
                } finally {
                    a.recycle()
                }

                inflater.inflate(R.menu.main, menu)

                setTitle(Config.activeReport)

                toggle.isDrawerIndicatorEnabled = true
                fab.visibility = View.VISIBLE
                selection_fab.visibility = View.GONE
                toolbar.visibility = View.GONE
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * isDrawerOpen only returns true only if m_drawerLayout != null, so
     * if this returns either _DRAWER, m_drawerLayout!!. calls are safe to make
     */
    private fun activeMode(): Mode {
        if (isDrawerOpen(NAV_DRAWER)) return Mode.NAV_DRAWER
        if (isDrawerOpen(FILTER_DRAWER)) return Mode.FILTER_DRAWER
        if (TaskList.selection.isNotEmpty()) return Mode.SELECTION
        return Mode.MAIN
    }

    private fun isDrawerOpen(drawer: Int): Boolean {
        if (drawer_layout == null) {
            Log.w(TAG, "Layout was null")
            return false
        }
        return drawer_layout.isDrawerOpen(drawer)
    }

    private fun closeDrawer(drawer: Int) {
        drawer_layout?.closeDrawer(drawer)
    }

    private fun shareVisibleTodoList() {
        val text =
        m_adapter!!.visibleTasks.asCliList()

        share(text, "Simpletask list")
    }

    private fun completeTasks(task: Task) {
        val tasks = ArrayList<Task>()
        tasks.add(task)
        completeTasks(tasks)
    }

    private fun uncompleteTasks(task: Task) {
        val tasks = ArrayList<Task>()
        tasks.add(task)
        uncompleteTasks(tasks)
    }
    private fun completeTasks(tasks: List<Task>) {
        TaskList.complete(tasks)
    }

    private fun uncompleteTasks(tasks: List<Task>) {
        TaskList.uncomplete(tasks)
    }

    private fun deferTasks(tasks: List<Task>, dateType: DateType) {
        var titleId = R.string.defer_due
        if (dateType === DateType.THRESHOLD) {
            titleId = R.string.defer_wait
        }
        val d = createDeferDialog(this, titleId, object : InputDialogListener {
            /* Suppress showCalendar deprecation message. It works fine on older devices
             * and newer devices don't really have an alternative */
            @Suppress("DEPRECATION")
            override fun onClick(input: String) {
                if (input == "pick") {
                    val today = DateTime.today(TimeZone.getDefault())
                    val dialog = DatePickerDialog(this@Simpletask, DatePickerDialog.OnDateSetListener { _, year, month, day ->
                        var startMonth = month
                        startMonth++
                        val date = DateTime.forDateOnly(year, startMonth, day)
                        TaskList.defer(date.format(Constants.DATE_FORMAT), tasks, dateType)
                    },
                            today.year!!,
                            today.month!! - 1,
                            today.day!!)

                    dialog.show()
                } else {
                    TaskList.defer(input, tasks, dateType)
                }

            }
        })
        d.show()
    }

    private fun deleteTasks(tasks: List<Task>) {
        val numTasks = tasks.size
        val title = getString(R.string.delete_task_title)
                    .replaceFirst(Regex("%s"), numTasks.toString())
        val delete = DialogInterface.OnClickListener { _, _ ->
            TaskList.removeAll(tasks)
            invalidateOptionsMenu()
        }

        showConfirmationDialog(this, R.string.delete_task_message, delete, title)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        Log.i(TAG, "onMenuItemSelected: " + item.itemId)
        val checkedTasks = TaskList.selection
        when (item.itemId) {
            androidId.home -> {
                when (activeMode()) {
                    Mode.NAV_DRAWER -> {
                        closeDrawer(NAV_DRAWER)
                    }
                    Mode.FILTER_DRAWER -> {
                        closeDrawer(FILTER_DRAWER)
                    }
                    Mode.SELECTION -> {
                        closeSelectionMode()
                    }
                    Mode.MAIN -> {
                        val toggle = m_drawerToggle ?: return true
                        toggle.onOptionsItemSelected(item)
                    }
                }
            }
            R.id.preferences -> startPreferencesActivity()
            R.id.context_delete -> deleteTasks(checkedTasks)
            R.id.context_select_all -> selectAllTasks()
            R.id.share -> {
                shareVisibleTodoList()
            }
            R.id.context_share -> {
                val shareText = selectedTasksAsTodoTxt()
                share( shareText, "Simpletask tasks")
            }
            R.id.help -> showHelp()
            R.id.sync -> TaskList.sync()
            R.id.open_file -> {
                // Check if we have SDCard permission for cloudless
                if (TaskWarrior.getWritePermission(this, REQUEST_PERMISSION)) {
                    m_app.browseForNewFile(this)
                }
            }
            R.id.clear_filter -> clearQuickFilter()
            R.id.update -> startAddTaskActivity()
            R.id.edit_rc -> startActivity(intentFor<EditRCActivity>())
            R.id.defer_due -> deferTasks(checkedTasks, DateType.DUE)
            R.id.defer_threshold -> deferTasks(checkedTasks, DateType.THRESHOLD)
            R.id.update_project -> updateProject(checkedTasks)
            R.id.update_tags -> updateTags(checkedTasks)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    internal fun clearQuickFilter() {
        Config.quickProjectsFilter = null
        Config.quickTagsFilter = null
        updateDrawers()
        m_adapter!!.setFilteredTasks()
    }

    private fun startAddTaskActivity() {
        Log.i(TAG, "Starting addTask activity")
        val intent = Intent(this, AddTask::class.java)
        startActivity(intent)
    }

    private fun startPreferencesActivity() {
        val settingsActivity = Intent(baseContext,
                Preferences::class.java)
        startActivityForResult(settingsActivity, REQUEST_PREFERENCES)
    }


    override fun onBackPressed() {
        when (activeMode()) {
            Mode.NAV_DRAWER -> {
                closeDrawer(NAV_DRAWER)
            }
            Mode.FILTER_DRAWER -> {
                closeDrawer(FILTER_DRAWER)
            }
            Mode.SELECTION -> {
                closeSelectionMode()
            }
            Mode.MAIN -> {
                return super.onBackPressed()
            }
        }
        return
    }

    private fun closeSelectionMode() {
        TaskList.clearSelection()
        invalidateOptionsMenu()
        m_adapter?.notifyDataSetChanged()
        m_adapter?.setFilteredTasks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_SEARCH == intent.action) {
            val currentIntent = getIntent()
            currentIntent.putExtra(SearchManager.QUERY, intent.getStringExtra(SearchManager.QUERY))
            setIntent(currentIntent)

        } else if (CalendarContract.ACTION_HANDLE_CUSTOM_EVENT == intent.action) {
            // Uri uri = Uri.parse(intent.getStringExtra(CalendarContract.EXTRA_CUSTOM_APP_URI));
            Log.w(TAG, "Not implemented search")
        } else if (intent.extras != null) {
            // Only change intent if it actually contains a filter
            setIntent(intent)
        }
        Config.lastScrollPosition = -1
        Log.i(TAG, "onNewIntent: " + intent)

    }

    private fun updateDrawers() {
        updateQuickFilterDrawer()
        updateReportDrawer()
    }

    private fun updateReportDrawer() {
        val names = TaskWarrior.filters().toTypedArray()
        nav_drawer.adapter = ArrayAdapter(this, R.layout.drawer_list_item, names)
        nav_drawer.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        val index = names.indexOf(Config.activeReport)
        nav_drawer.setSelection(if (index!=-1) index else 0)
        nav_drawer.isLongClickable = true
        nav_drawer.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->

//            MainFilter = names[position]
//            val intent = intent
//            MainFilter.saveInIntent(intent)
//            setIntent(intent)
//            MainFilter.saveInPrefs(Config.prefs)
            Config.activeReport = names[position]
            closeDrawer(NAV_DRAWER)
            closeSelectionMode()
            TaskList.reload("report changed")
            updateDrawers()
        }
        nav_drawer.onItemLongClickListener = OnItemLongClickListener { _, view, position, _ ->
//            val filter = filters[position]
//            val prefsName = filter.prefName!!
//            val popupMenu = PopupMenu(this@Simpletask, view)
//            popupMenu.setOnMenuItemClickListener { item ->
//                val menuId = item.itemId
//                when (menuId) {
//                    R.id.menu_saved_filter_delete -> deleteSavedFilter(prefsName)
//                    R.id.menu_saved_filter_shortcut -> createFilterShortcut(filter)
//                    R.id.menu_saved_filter_rename -> renameSavedFilter(prefsName)
//                    R.id.menu_saved_filter_update -> updateSavedFilter(prefsName)
//                    else -> {
//                    }
//                }
//                true
//            }
//            val inflater = popupMenu.menuInflater
//            inflater.inflate(R.menu.saved_filter, popupMenu.menu)
//            popupMenu.show()
            true
        }
    }

    fun createFilterShortcut(filterName: String) {
        val shortcut = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
        val target = Intent(Constants.INTENT_START_FILTER)

        target.putExtra("name", filterName)

        // Setup target intent for shortcut
        shortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)

        // Set shortcut icon
        val iconRes = Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher)
        shortcut.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconRes)
        shortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME, filterName)
        sendBroadcast(shortcut)
    }


    private fun updateQuickFilterDrawer() {
        val projectsInDrawer = TaskList.projects.union(Config.quickProjectsFilter?.toList()?:ArrayList<String>())
        val tagsInDrawer = TaskList.tags.union(Config.quickTagsFilter?.toList()?:ArrayList<String>())
        val decoratedContexts = alfaSortList(projectsInDrawer, Config.sortCaseSensitive, prefix="-").map { "@" + it }
        val decoratedProjects = alfaSortList(tagsInDrawer, Config.sortCaseSensitive, prefix="-").map { "+" + it }
        val drawerAdapter = DrawerAdapter(layoutInflater,
                Config.projectTerm,
                decoratedContexts,
                Config.tagTerm,
                decoratedProjects)

        filter_drawer.adapter = drawerAdapter
        filter_drawer.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        filter_drawer.onItemClickListener = DrawerItemClickListener()

        Config.quickProjectsFilter?.let { it
                    .map { drawerAdapter.getIndexOf("@"+ it) }
                    .filter { it != -1 }
                    .forEach { filter_drawer.setItemChecked(it, true) }
        }

        Config.quickProjectsFilter?.let { it
                    .map { drawerAdapter.getIndexOf("+" + it) }
                    .filter { it != -1 }
                    .forEach { filter_drawer.setItemChecked(it, true) }
        }
        filter_drawer.deferNotifyDataSetChanged()
    }

    val listView: RecyclerView?
        get() {
            val lv = list
            return lv
        }

    private var progressDialog: ProgressDialog? = null

    fun showProgress(show: Boolean) {
        if (show && progressDialog == null) {
            progressDialog = indeterminateProgressDialog(R.string.updating)
        } else {
            progressDialog?.cancel()
            progressDialog = null
        }
    }

    class TaskViewHolder(itemView: View, val viewType : Int) : RecyclerView.ViewHolder(itemView)


    inner class TaskAdapter(private val m_inflater: LayoutInflater) : RecyclerView.Adapter <TaskViewHolder>() {
        var visibleTasks : List<Task> = ArrayList<Task>()
        override fun getItemCount(): Int {
            return visibleTasks.size + 1
        }

        override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): TaskViewHolder {
            val view = when (viewType) {
                0 -> {
                    // Header
                    m_inflater.inflate(R.layout.list_header, parent, false)
                }
                1 -> {
                    // Task
                    m_inflater.inflate(R.layout.list_item, parent, false)
                }
                else -> {
                    // Empty at end
                    m_inflater.inflate(R.layout.empty_list_item, parent, false)
                }

            }
            return TaskViewHolder(view, viewType)
        }

        override fun onBindViewHolder(holder: TaskViewHolder?, position: Int) {
            if (holder == null) return
            when (holder.viewType) {
                1 -> bindTask(holder, position)
                else -> return
            }
        }


        fun bindTask (holder : TaskViewHolder, position: Int) {
            val line = visibleTasks[position]
            val item = line
            val view = holder.itemView
            val taskText = view.tasktext
            val taskAge = view.taskage
            val taskDue = view.taskdue
            val taskThreshold = view.taskthreshold

            val task = item

            if (Config.showCompleteCheckbox) {
                view.checkBox.visibility = View.VISIBLE
            } else {
                view.checkBox.visibility = View.GONE
            }

            val completed = task.isCompleted
            val text = task.displayText

            val startColorSpan = text.length
            val tags = task.tags.map { "+"+it }.joinToString(" ")
            val project = task.project?.let {" @" + it} ?: ""
            val fullText = (text + project + " " + tags).trim()
            val ss = SpannableString(fullText)

            ss.setSpan(ForegroundColorSpan(Color.GRAY), startColorSpan, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            taskAge.textSize = textSize * Config.dateBarRelativeSize
            taskDue.textSize = textSize * Config.dateBarRelativeSize
            taskThreshold.textSize = textSize * Config.dateBarRelativeSize

            val cb = view.checkBox
            taskText.text = ss
            taskText.textSize = textSize
            handleEllipsis(taskText)

            if (completed) {
                // Log.i( "Striking through " + task.getText());
                taskText.paintFlags = taskText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                taskAge.paintFlags = taskAge.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                cb.setOnClickListener({
                    uncompleteTasks(item)
                    // Update the tri state checkbox
                    if (activeMode() == Mode.SELECTION) invalidateOptionsMenu()
                })
            } else {
                taskText.paintFlags = taskText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                taskAge.paintFlags = taskAge.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

                cb.setOnClickListener {
                    completeTasks(item)
                    // Update the tri state checkbox
                    if (activeMode() == Mode.SELECTION) invalidateOptionsMenu()
                }

            }
            if (task.isDeleted) {
                view.deletedIndicator.visibility = View.VISIBLE
                view.checkBox.isEnabled = false
            } else {
                view.deletedIndicator.visibility = View.GONE
                view.checkBox.isEnabled = true
            }


            if (task.annotations.isNotEmpty()) {
                view.annotationbar.visibility=View.VISIBLE
                view.annotationbar.annotations.text = task.annotations.map{"- $it"}.joinToString("\n")
                view.annotationbar.annotations.textSize = textSize * Config.dateBarRelativeSize
            } else {
                view.annotationbar.visibility=View.GONE
            }

            cb.isChecked = completed

            val relAge = getRelativeAge(task, m_app)
            val relDue = getRelativeDueDate(task, m_app)
            val relativeThresholdDate = getRelativeWaitDate(task, m_app)
            if (!isEmptyOrNull(relAge)) {
                taskAge.text = relAge
                taskAge.visibility = View.VISIBLE
            } else {
                taskAge.text = ""
                taskAge.visibility = View.GONE
            }

            if (relDue != null) {
                taskDue.text = relDue
                taskDue.visibility = View.VISIBLE
            } else {
                taskDue.text = ""
                taskDue.visibility = View.GONE
            }
            if (!isEmptyOrNull(relativeThresholdDate)) {
                taskThreshold.text = relativeThresholdDate
                taskThreshold.visibility = View.VISIBLE
            } else {
                taskThreshold.text = ""
                taskThreshold.visibility = View.GONE
            }
            // Set selected state
            view.isActivated = TaskList.isSelected(item)

            // Set click listeners
            view.setOnClickListener { it ->

                val newSelectedState = !TaskList.isSelected(item)
                if (newSelectedState) {
                    TaskList.selectTask(item)
                } else {
                    TaskList.unSelectTask(item)
                }
                it.isActivated = newSelectedState
                invalidateOptionsMenu()
            }

            view.setOnLongClickListener {
                val links = ArrayList<String>()
                val actions = ArrayList<String>()
                val t = item
                for (link in t.links) {
                    actions.add(ACTION_LINK)
                    links.add(link)
                }
                if (actions.size != 0) {

                    val titles = ArrayList<String>()
                    for (i in links.indices) {
                        when (actions[i]) {
                            ACTION_SMS -> titles.add(i, getString(R.string.action_pop_up_sms) + links[i])
                            ACTION_PHONE -> titles.add(i, getString(R.string.action_pop_up_call) + links[i])
                            else -> titles.add(i, links[i])
                        }
                    }
                    val build = AlertDialog.Builder(this@Simpletask)
                    build.setTitle(R.string.task_action)
                    val titleArray = titles.toArray<String>(arrayOfNulls<String>(titles.size))
                    build.setItems(titleArray) { _, which ->
                        val actionIntent: Intent
                        val url = links[which]
                        Log.i(TAG, "" + actions[which] + ": " + url)
                        when (actions[which]) {
                            ACTION_LINK ->
                                try {
                                    actionIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    startActivity(actionIntent)
                                } catch (e: ActivityNotFoundException) {
                                    Log.i(TAG, "No handler for task action $url")
                                    longToast("No handler for $url" )
                                }
                            ACTION_PHONE -> {
                                actionIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(url)))
                                startActivity(actionIntent)
                            }
                            ACTION_SMS -> {
                                actionIntent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + Uri.encode(url)))
                                startActivity(actionIntent)
                            }
                            ACTION_MAIL -> {
                                actionIntent = Intent(Intent.ACTION_SEND, Uri.parse(url))
                                actionIntent.putExtra(android.content.Intent.EXTRA_EMAIL,
                                        arrayOf(url))
                                actionIntent.type = "text/plain"
                                startActivity(actionIntent)
                            }
                        }
                    }
                    build.create().show()
                }
                true
            }
        }


        internal fun setFilteredTasks() {
            TaskList.queue("setFilteredTasks") {
                runOnUiThread {
                    showProgress(true)
                }
                Log.i(TAG, "setFilteredTasks called: " + TaskList)
                val newVisibleTasks = TaskList.applyQuickFilter(Config.quickProjectsFilter, Config.quickTagsFilter).toList()

                runOnUiThread {
                    // Replace the array in the main thread to prevent OutOfIndex exceptions
                    visibleTasks = newVisibleTasks
                    notifyDataSetChanged()
                    showProgress(false)
                    if (Config.lastScrollPosition != -1) {
                        val manager = listView?.layoutManager as LinearLayoutManager?
                        val position = Config.lastScrollPosition
                        val offset = Config.lastScrollOffset
                        Log.i(TAG, "Restoring scroll offset $position, $offset")
                        manager?.scrollToPositionWithOffset(position, offset )
                        Config.lastScrollPosition = -1
                    }
                }
            }
        }


        /*
        ** Get the adapter position for task
        */
        fun getPosition(task: Task): Int {
            return visibleTasks.indexOf(task)
        }

        fun getTask(n: Int) : Task? {
            return visibleTasks[n]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getItemViewType(position: Int): Int {
            if (position == visibleTasks.size) {
                return 2
            } else {
                return 1
            }
        }
    }

    private fun handleEllipsis(taskText: TextView) {
        val noEllipsizeValue = "no_ellipsize"
        val ellipsizeKey = m_app.getString(R.string.task_text_ellipsizing_pref_key)
        val ellipsizePref = Config.prefs.getString(ellipsizeKey, noEllipsizeValue)

        if (noEllipsizeValue != ellipsizePref) {
            val truncateAt: TextUtils.TruncateAt?
            when (ellipsizePref) {
                "start" -> truncateAt = TextUtils.TruncateAt.START
                "end" -> truncateAt = TextUtils.TruncateAt.END
                "middle" -> truncateAt = TextUtils.TruncateAt.MIDDLE
                "marquee" -> truncateAt = TextUtils.TruncateAt.MARQUEE
                else -> truncateAt = null
            }

            if (truncateAt != null) {
                taskText.maxLines = 1
                taskText.setHorizontallyScrolling(true)
                taskText.ellipsize = truncateAt
            } else {
                Log.w(TAG, "Unrecognized preference value for task text ellipsis: {} !" + ellipsizePref)
            }
        }
    }

    private fun updateProject(checkedTasks: List<Task>) {
        val allItems = TaskList.projects
        allItems.add(0,"")
        allItems.sort()


        val view = layoutInflater.inflate(R.layout.update_project_dialog, null, false)
        val builder = AlertDialog.Builder(this)
        builder.setView(view)

        val rcv = view.current_projects_list
        rcv.adapter = ArrayAdapter(this, R.layout.simple_list_item_single_choice, allItems)
        val ed = view.new_project_text

        builder.setPositiveButton(R.string.ok) { _, _ ->
            val newText = ed.text.toString()
            if (newText.isNotEmpty()) {
                TaskList.updateProject(checkedTasks, newText)
                return@setPositiveButton
            }
        }
        builder.setNegativeButton(R.string.cancel) { _, _ -> }
        val dialog = builder.create()

        rcv.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            TaskList.updateProject(checkedTasks, allItems[position])
            dialog.cancel()
        }

        dialog.setTitle(Config.projectTerm)
        dialog.show()
    }

    private fun updateTags(checkedTasks: List<Task>) {
        val checkedTaskItems = ArrayList<HashSet<String>>()
        val allItems = TaskList.tags
        checkedTasks.forEach {
            val items = HashSet<String>()
            items.addAll(it.tags)
            checkedTaskItems.add(items)
        }

        // Determine items on all tasks (intersection of the sets)
        val onAllTasks = checkedTaskItems.intersection()

        // Determine items on some tasks (union of the sets)
        var onSomeTasks = checkedTaskItems.union()
        onSomeTasks -= onAllTasks

        allItems.removeAll(onAllTasks)
        allItems.removeAll(onSomeTasks)

        val sortedAllItems = ArrayList<String>()
        sortedAllItems += alfaSortList(onAllTasks, Config.sortCaseSensitive)
        sortedAllItems += alfaSortList(onSomeTasks, Config.sortCaseSensitive)
        sortedAllItems += alfaSortList(allItems.toSet(), Config.sortCaseSensitive)

        val view = layoutInflater.inflate(R.layout.update_tags_dialog, null, false)
        val builder = AlertDialog.Builder(this)
        builder.setView(view)

        val itemAdapter = ItemDialogAdapter(sortedAllItems, onAllTasks.toHashSet(), onSomeTasks.toHashSet())
        val rcv = view.current_items_list
        rcv.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(this)
        rcv.layoutManager = layoutManager
        rcv.adapter = itemAdapter
        val ed = view.new_item_text
        builder.setPositiveButton(R.string.ok) { _, _ ->
            val updatedValues = itemAdapter.currentState
            val tagsToAdd = ArrayList<String>()
            val tagsToRemove = ArrayList<String>()
            for (i in 0..updatedValues.lastIndex) {
                when (updatedValues[i] ) {
                    false -> tagsToRemove.add(sortedAllItems[i])
                    true -> tagsToAdd.add(sortedAllItems[i])
                    }
                }

            val newText = ed.text.toString()
            if (newText.isNotEmpty()) {
                    tagsToAdd.add(newText)
            }
            TaskList.updateTags(checkedTasks, tagsToAdd, tagsToRemove)
        }
        builder.setNegativeButton(R.string.cancel) { _, _ -> }
        // Create the AlertDialog
        val dialog = builder.create()

        dialog.setTitle(Config.tagTerm)
        dialog.show()
    }


    private inner class DrawerItemClickListener : AdapterView.OnItemClickListener {

        override fun onItemClick(parent: AdapterView<*>, view: View, position: Int,
                                 id: Long) {
            val tags: ArrayList<String>
            val lv = parent as ListView
            val adapter = lv.adapter as DrawerAdapter
            if (adapter.projectsHeaderPosition == position) {
                updateDrawers()
            }
            if (adapter.contextHeaderPosition == position) {
                updateDrawers()
            } else {
                tags = getCheckedItems(lv, true)
                val filteredContexts = HashSet<String>()
                val filteredProjects = HashSet<String>()

                for (tag in tags) {
                    if (tag.startsWith("+")) {
                        filteredProjects.add(tag.substring(1))
                    } else if (tag.startsWith("@")) {
                        filteredContexts.add(tag.substring(1))
                    }
                }
                Config.quickProjectsFilter = filteredContexts
                Config.quickTagsFilter = filteredProjects
            }

            if (!Config.hasKeepSelection) {
                TaskList.clearSelection()
            }
            m_adapter!!.setFilteredTasks()
        }
    }

    companion object {

        private val REQUEST_PREFERENCES = 2
        private val REQUEST_PERMISSION = 3

        private val ACTION_LINK = "link"
        private val ACTION_SMS = "sms"
        private val ACTION_PHONE = "phone"
        private val ACTION_MAIL = "mail"

        val URI_BASE = Uri.fromParts("Simpletask", "", null)!!
        val URI_SEARCH = Uri.withAppendedPath(URI_BASE, "search")!!
        private val TAG = "Simpletask"
    }
}




