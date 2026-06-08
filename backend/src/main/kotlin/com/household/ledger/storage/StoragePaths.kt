package com.household.ledger.storage

import java.io.File
import org.slf4j.LoggerFactory

object StoragePaths {
    private val logger = LoggerFactory.getLogger(StoragePaths::class.java)

    var customDataDir: File? = null

    private val projectRoot: File by lazy {
        findProjectRoot(File(System.getProperty("user.dir") ?: "").absoluteFile)
    }

    private val backendModuleDir: File by lazy {
        File(projectRoot, "backend").canonicalFile
    }

    // Historical canonical root: <repo>/backend/backend/data.
    val dataDir: File by lazy {
        if (customDataDir != null) {
            logger.info("StoragePaths: Using custom Android data directory: ${customDataDir!!.absolutePath}")
            customDataDir!!.ensureDirectory()
        } else {
            logger.info("StoragePaths: Using fallback desktop backend data directory.")
            File(backendModuleDir, "backend/data").ensureDirectory()
        }
    }

    val databaseFile: File by lazy {
        File(dataDir, "ledger.db").absoluteFile
    }

    val backupsDir: File by lazy {
        File(dataDir, "backups").ensureDirectory()
    }

    private fun findProjectRoot(start: File): File {
        return generateSequence(start) { it.parentFile }
            .firstOrNull { candidate ->
                File(candidate, "settings.gradle.kts").exists() &&
                    File(candidate, "backend/src/main/kotlin").exists()
            }
            ?: start
    }

    private fun File.ensureDirectory(): File {
        if (!exists()) {
            mkdirs()
        }
        return canonicalFile
    }
}
