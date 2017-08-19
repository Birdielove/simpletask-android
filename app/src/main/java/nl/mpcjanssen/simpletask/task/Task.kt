package nl.mpcjanssen.simpletask.task

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern


data class Task(
        val json: JSONObject,
        val uuid: String,
        val description: String,
        val annotations: List<String>,
        val project: String?,
        val tags: List<String>,
        val urgency: Double,
        val status: String,
        val dueDate: String?,
        val waitDate: String?,
        val endDate: String?,
        val entryDate: String) {

    val isCompleted: Boolean
        get() {
            return status == "completed"
        }

    val isDeleted: Boolean
        get() {
            return status == "deleted"
        }


    fun inFuture(today: String): Boolean {
            if (waitDate != null) {
                return (waitDate > today)
            } else {
                return false
            }
        }

    val displayText: String = description

    val asCliTxt: String
    get() {
        val resultBuilder = StringBuilder()
        resultBuilder.append(description.trim())
        resultBuilder.append(" entry:$entryDate")

        dueDate?.let {
            resultBuilder.append(" due:$it")
        }
        waitDate?.let {
            resultBuilder.append(" wait:$it")
        }
        project?.let {
            resultBuilder.append(" proj:$project")
        }
        if (tags.isNotEmpty()) {
            val tagsString = tags.map { "+$it" }.joinToString(" ")
            resultBuilder.append(" $tagsString")
        }

        resultBuilder.append(" status:${status}")
        return resultBuilder.toString()
    }

    val links : List<String>
    get() {
        val text = description + " " + annotations.joinToString(" ")
        return MATCH_URI.findAll(text).map { it.value }.toList()
    }

    fun matchesQuickFilter(filterProjects: Set<String>?, filterTags: Set<String>?) : Boolean {
        val matchProjects = when {
            filterProjects == null -> true
            filterProjects.isEmpty() -> true
            project in filterProjects -> true
            filterProjects.contains("-") && project.isNullOrBlank() -> true
            else -> false
        }
        val matchTags = when {
            filterTags == null -> true
            filterTags.isEmpty() -> true
            tags.intersect(filterTags).isNotEmpty()  -> true
            filterTags.contains("-") && tags.isEmpty() -> true
            else -> false
        }
        return matchProjects && matchTags
    }

    fun getHeader(sort: String, empty: String): String {
        if (sort.contains("by_context")) {
            if (project != null) {
                return project
            } else {
                return empty
            }
        } else if (sort.contains("by_project")) {
            if (tags.isNotEmpty()) {
                return tags.first()
            } else {
                return empty
            }
        } else if (sort.contains("by_threshold_date")) {
            return waitDate ?: entryDate ?: empty
        } else if (sort.contains("by_prio")) {
            return urgency.toInt().toString()
        } else if (sort.contains("by_due_date")) {
            return dueDate ?: empty
        }
        return ""
    }


    companion object {
        const val DATE_FORMAT = "YYYY-MM-DD"
        val MATCH_URI = Regex("[a-z]+://(\\S+)")
        fun fromJSON (jsonStr: String) : Task {
            val json = JSONObject(jsonStr)
            val uuid = json.getString("uuid")
            val desc = json.getString("description")
            val endDate = json.optString("end", null)?.let {
                val year = it.slice(0..3)
                val month = it.slice(4..5)
                val day = it.slice(6..7)
                "$year-$month-$day"
            }
            val entryDate = json.getString("entry").let {
                val year = it.slice(0..3)
                val month = it.slice(4..5)
                val day = it.slice(6..7)
                "$year-$month-$day"
            }
            val tags = ArrayList<String>()
            json.optJSONArray("tags")?.let {
                for (i in 0..it.length() - 1) {
                    tags.add(it.getString(i))
                }
            }
            val annotations = ArrayList<String>()
            json.optJSONArray("annotations")?.let {
                for (i in 0..it.length() - 1) {
                    val annotationObj = it.getJSONObject(i)
                    annotations.add(annotationObj.getString("description"))
                }
            }
            val project = json.optString("project", null)

            val status =  json.getString("status")

            val waitDate = json.optString("wait", null)?.let {
                val year = it.slice(0..3)
                val month = it.slice(4..5)
                val day = it.slice(6..7)
                "$year-$month-$day"
            }

            val urgency = json.getDouble("urgency")

            return Task(json, uuid, desc, annotations, project, tags, urgency, status, null, waitDate, endDate, entryDate )
        }
    }
}

fun List<Task>.asCliList(): String {
        return this.map { it.asCliTxt }.joinToString ("\n")
    }

