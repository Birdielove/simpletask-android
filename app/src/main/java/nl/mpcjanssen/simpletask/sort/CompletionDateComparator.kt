package nl.mpcjanssen.simpletask.sort

import nl.mpcjanssen.simpletask.task.Task
import java.util.*

class CompletionDateComparator : Comparator<Task> {

    override fun compare(a: Task?, b: Task?): Int {
        if (a === b) {
            return 0
        } else if (a == null) {
            return -1
        } else if (b == null) {
            return 1
        }
        val aDate = a.completionDate
        val bDate = b.completionDate
        if ( aDate == null && bDate == null) {
            return 0
        } else if (aDate == null) {
            return 1
        } else if (bDate == null) {
            return -1
        }
        return aDate.compareTo(bDate)
    }
}
