package app.lenews.db

import androidx.room.Room
import org.koin.dsl.module

val dbModule = module {

    single(createdAtStart = true) {
        Room.databaseBuilder(get(), Database::class.java, "lenews-db")
            .addCallback(AnalyzeOnCreate)
            // The schema restarts at version 1 with no migration from the six
            // versions this fork inherited. A phone still holding one of them is
            // holding a database of a shape nothing here can read, so it is
            // dropped and refilled by the next sync.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }
}
