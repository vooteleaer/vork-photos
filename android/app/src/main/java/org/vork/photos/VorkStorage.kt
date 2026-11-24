package org.vork.photos

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * vØrk storage, which this app uses through the vØrk storage app: its sockets are in
 * that app's files, so it connects for this one and hands the connection
 * over (its access service), once the user has let this app in to the
 * libraries chosen there. The core asks for each connection through
 * [connect].
 */
object VorkStorage {
    const val PACKAGE = "org.vork.storage"
    private const val DESCRIPTOR = "org.vork.IAccess"
    private const val OPEN = IBinder.FIRST_CALL_TRANSACTION

    enum class Problem { NOT_INSTALLED, NOT_LET_IN, NOT_RUNNING }

    /** Why the last connection failed; null once one works. */
    var problem by mutableStateOf<Problem?>(null)
        private set

    private lateinit var context: Context
    @Volatile private var binder: IBinder? = null
    @Volatile private var bound: CountDownLatch? = null

    fun start(context: Context) {
        this.context = context.applicationContext
        Core.init(VorkStorage::class.java)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            bound?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }

        override fun onBindingDied(name: ComponentName?) {
            binder = null
            context.unbindService(this)
            bound = null
        }
    }

    /** The access service, bound now if it isn't (off the main thread). */
    @Synchronized
    private fun service(): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        val latch = bound ?: CountDownLatch(1).also {
            bound = it
            val intent = Intent("org.vork.storage.action.ACCESS").setPackage(PACKAGE)
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bound = null
                return null
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return binder
    }

    /** A connection to vorkd for the core: a descriptor it owns, or -1. */
    @JvmStatic
    fun connect(): Int {
        val b = service() ?: run {
            problem = Problem.NOT_INSTALLED
            return -1
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(OPEN, data, reply, 0)
            return when (reply.readInt()) {
                0 -> {
                    problem = null
                    ParcelFileDescriptor.CREATOR.createFromParcel(reply).detachFd()
                }
                1 -> {
                    problem = Problem.NOT_LET_IN
                    -1
                }
                else -> {
                    problem = Problem.NOT_RUNNING
                    -1
                }
            }
        } catch (e: DeadObjectException) {
            binder = null
            problem = Problem.NOT_RUNNING
            return -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Asks the user (in the vØrk storage app) to let this app in, or change what it has. */
    fun letIn(): Intent = Intent("org.vork.storage.action.LET_IN")
        .setPackage(PACKAGE)
        .putExtra("for", "photos")
        .putExtra("write", true)
        .putExtra("why", "Photos shows the pictures in your libraries, and backs up the ones taken on this phone into one of them.")

    /** The vØrk storage app itself (its Announces, joining, settings). */
    fun open(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)
}

class PhotosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VorkStorage.start(this)
        CrashReports.install(this, Core::crashDir, Core::crashReport)
        Backup.schedule(this)
    }
}
