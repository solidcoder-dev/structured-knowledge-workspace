package dev.skw.application.port.out

import dev.skw.domain.entry.Entry

interface EntryRepository {
    fun save(entry: Entry): Entry
}
