package top.tianyan.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuildScriptRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BuildScriptDao
    private lateinit var repository: RoomBuildScriptRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.buildScriptDao()
        repository = RoomBuildScriptRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ensureBuiltinScriptsInitializesStandardScripts() = runBlocking {
        repository.ensureBuiltinScripts()

        val androidScript = repository.findScript("builtin-android")
        assertNotNull(androidScript)
        assertTrue(androidScript!!.isBuiltin)
        assertTrue(androidScript.content.contains("tianyan-build.sh android"))

        val flutterScript = repository.findScript("builtin-flutter")
        assertNotNull(flutterScript)
        assertTrue(flutterScript!!.isBuiltin)
        assertTrue(flutterScript.content.contains("tianyan-build.sh flutter"))
    }

    @Test
    fun legacyBrokenStubIsAutomaticallyMigrated() = runBlocking {
        val legacyBrokenAndroid = "#!/bin/sh\nset -eu\nPROJECT_DIR=\"\${1:-.}\"\nTASK=\"\${2:-assembleDebug}\"\ncd \"\$PROJECT_DIR\"\nif [ -f ./gradlew ]; then\n    chmod +x ./gradlew\n    ./gradlew \"\$TASK\" --no-daemon --max-workers=2\nelif command -v gradle >/dev/null 2>&1; then\n    gradle \"\$TASK\" --no-daemon --max-workers=2\nelif [ -x /opt/tianyan/bin/gradle ]; then\n    /opt/tianyan/bin/gradle \"\$TASK\" --no-daemon --max-workers=2\nelse\n    echo '未找到可用的 Gradle 环境，请检查是否已安装 Android 基础套件' >&2\n    exit 127\nfi\n"
        dao.upsertScript(
            BuildScriptEntity(
                id = "builtin-android",
                name = "标准 Android",
                description = "旧版占位桩",
                projectType = "ANDROID",
                content = legacyBrokenAndroid,
                isBuiltin = true,
                createdAt = 1000L,
                updatedAt = 1000L,
            )
        )

        repository.ensureBuiltinScripts()

        val updated = repository.findScript("builtin-android")
        assertNotNull(updated)
        assertTrue(updated!!.content.contains("tianyan-build.sh android"))
    }

    @Test
    fun bindAndUnbindWorkflow() = runBlocking {
        repository.ensureBuiltinScripts()
        repository.bind("my-app", "builtin-android")

        val binding = repository.findBinding("my-app")
        assertNotNull(binding)
        assertEquals("builtin-android", binding!!.scriptId)

        repository.unbind("my-app")
        assertNull(repository.findBinding("my-app"))
    }
}
