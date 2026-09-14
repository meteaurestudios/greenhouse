package org.androidaudioplugin.greenhouse.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.androidaudioplugin.AudioPluginViewService
import org.androidaudioplugin.hosting.GuiHelper
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GreenhouseSurfaceHost"
private const val PREFERRED_SIZE_TIMEOUT_MS = 3000L
private const val RECONNECT_SUPPRESSION_LOG_MSG = "Ignoring subsequent onServiceConnected event on active/dead connection"
private const val DISCONNECT_SERVICE_MSG = "Remote UI service disconnected unexpectedly"
private const val DISCONNECT_BINDING_DIED_MSG = "Remote UI IPC binding died"
private const val DISCONNECT_NULL_BINDING_MSG = "Remote UI returned null binding"

/**
 * Robust host for remote AAP plugin native GUI surfaces.
 *
 * Wraps [AudioPluginViewService] IPC communication with crash resilience:
 * - Guarantees unbindService() is called on service disconnect or binding death, preventing stale ServiceDispatcher leaks.
 * - Protects coroutine continuations and listeners from duplicate execution if Android auto-restarts a dying service.
 * - Forwards remote disconnection events to the UI layer for smooth fallback handling.
 */
class GreenhouseSurfaceControlHost(
    private val context: Context,
    private val pluginPackageName: String,
    private val pluginId: String,
    private val instanceId: Int
) : AutoCloseable {

    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((reason: String) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    val contentSizeChangedListeners = mutableListOf<(Int, Int) -> Unit>()

    private val messageHandlerThread = HandlerThread("GreenhouseUiMessenger").apply { start() }

    private var activeConnection: SafeServiceConnection? = null
    private var surfacePackage: SurfaceControlViewHost.SurfacePackage? = null
    private var isDisposed = false

    private val incomingMessenger = Messenger(object : Handler(messageHandlerThread.looper) {
        override fun handleMessage(msg: Message) {
            val opcode = msg.data.getInt(AudioPluginViewService.MESSAGE_KEY_OPCODE)

            if (opcode == AudioPluginViewService.OPCODE_CONTENT_SIZE_CHANGED) {
                val width = msg.data.getInt(AudioPluginViewService.MESSAGE_KEY_CONTENT_WIDTH)
                val height = msg.data.getInt(AudioPluginViewService.MESSAGE_KEY_CONTENT_HEIGHT)

                contentSizeChangedListeners.forEach { listener ->
                    listener(width, height)
                }
            } else {
                val receivedPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    msg.data.getParcelable(
                        AudioPluginViewService.MESSAGE_KEY_SURFACE_PACKAGE,
                        SurfaceControlViewHost.SurfacePackage::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    msg.data.getParcelable(AudioPluginViewService.MESSAGE_KEY_SURFACE_PACKAGE) as? SurfaceControlViewHost.SurfacePackage
                }

                if (receivedPackage != null && !isDisposed) {
                    surfacePackage?.release()
                    surfacePackage = receivedPackage

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            surfaceView.setChildSurfacePackage(receivedPackage)
                            onConnected?.invoke()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to setChildSurfacePackage", t)
                            onError?.invoke(t)
                        }
                    }
                }
            }
        }
    })

    val surfaceView: SurfaceView by lazy {
        GreenhouseSurfaceView(context, this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setZOrderOnTop(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun getPreferredSizeOrFallback(fallbackWidth: Int, fallbackHeight: Int): GuiHelper.Size {
        return withContext(Dispatchers.IO) {
            val resultDeferred = CompletableDeferred<GuiHelper.Size>()
            val replyHandler = object : Handler(messageHandlerThread.looper) {
                override fun handleMessage(msg: Message) {
                    val width = msg.data.getInt(AudioPluginViewService.MESSAGE_KEY_PREFERRED_WIDTH)
                    val height = msg.data.getInt(AudioPluginViewService.MESSAGE_KEY_PREFERRED_HEIGHT)

                    val resolvedWidth = if (width > 0) {
                        width
                    } else {
                        fallbackWidth
                    }

                    val resolvedHeight = if (height > 0) {
                        height
                    } else {
                        fallbackHeight
                    }

                    resultDeferred.complete(GuiHelper.Size(resolvedWidth, resolvedHeight))
                }
            }
            val replyMessenger = Messenger(replyHandler)

            val connection = SafeServiceConnection(
                context = context,
                onConnectedAction = { conn ->
                    try {
                        val msg = Message.obtain().apply {
                            data = bundleOf(
                                AudioPluginViewService.MESSAGE_KEY_OPCODE to AudioPluginViewService.OPCODE_GET_PREFERRED_SIZE,
                                AudioPluginViewService.MESSAGE_KEY_PLUGIN_ID to pluginId,
                                AudioPluginViewService.MESSAGE_KEY_INSTANCE_ID to instanceId
                            )
                            replyTo = replyMessenger
                        }

                        conn.outgoingMessenger?.send(msg)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to send GET_PREFERRED_SIZE message: ${t.message}")
                        resultDeferred.complete(GuiHelper.Size(fallbackWidth, fallbackHeight))
                    }
                },
                onDisconnectedAction = {
                    resultDeferred.complete(GuiHelper.Size(fallbackWidth, fallbackHeight))
                }
            )

            val intent = Intent().setClassName(pluginPackageName, AudioPluginViewService::class.java.name)
            val bound = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to bind for preferred size: ${t.message}")
                false
            }

            if (!bound) {
                return@withContext GuiHelper.Size(fallbackWidth, fallbackHeight)
            }

            val computedSize = withTimeoutOrNull(PREFERRED_SIZE_TIMEOUT_MS) {
                resultDeferred.await()
            } ?: GuiHelper.Size(fallbackWidth, fallbackHeight)

            connection.unbind()
            computedSize
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun connect(width: Int, height: Int) {
        withContext(Dispatchers.Main) {
            val lp = surfaceView.layoutParams ?: LayoutParams(width, height).also {
                surfaceView.layoutParams = it
            }
            lp.width = width
            lp.height = height
            surfaceView.requestLayout()
        }

        withContext(Dispatchers.IO) {
            val connection = SafeServiceConnection(
                context = context,
                onConnectedAction = { conn ->
                    try {
                        val data = bundleOf(
                            AudioPluginViewService.MESSAGE_KEY_OPCODE to AudioPluginViewService.OPCODE_CONNECT,
                            AudioPluginViewService.MESSAGE_KEY_HOST_TOKEN to surfaceView.hostToken,
                            AudioPluginViewService.MESSAGE_KEY_DISPLAY_ID to (surfaceView.display?.displayId ?: 0),
                            AudioPluginViewService.MESSAGE_KEY_PLUGIN_ID to pluginId,
                            AudioPluginViewService.MESSAGE_KEY_INSTANCE_ID to instanceId,
                            AudioPluginViewService.MESSAGE_KEY_WIDTH to width,
                            AudioPluginViewService.MESSAGE_KEY_HEIGHT to height
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            surfaceView.rootSurfaceControl?.inputTransferToken?.let { token ->
                                data.putParcelable(AudioPluginViewService.MESSAGE_KEY_INPUT_TRANSFER_TOKEN, token)
                            }
                        }

                        val msg = Message.obtain().apply {
                            this.data = data
                            replyTo = incomingMessenger
                        }

                        conn.outgoingMessenger?.send(msg)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to send connect message to AudioPluginViewService", t)
                        onError?.invoke(t)
                    }
                },
                onDisconnectedAction = { reason ->
                    handleDisconnection(reason)
                }
            )

            activeConnection?.unbind()
            activeConnection = connection

            val intent = Intent().setClassName(pluginPackageName, AudioPluginViewService::class.java.name)
            val bound = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind AudioPluginViewService", t)
                onError?.invoke(t)
                false
            }

            if (!bound) {
                val error = IllegalStateException("Failed to bind AudioPluginViewService for $pluginPackageName")
                onError?.invoke(error)
                throw error
            }
        }
    }

    fun show() {
        surfaceView.visibility = View.VISIBLE
    }

    fun hide() {
        surfaceView.visibility = View.GONE
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun resize(width: Int, height: Int) {
        val messenger = activeConnection?.outgoingMessenger

        if (messenger != null) {
            try {
                val msg = Message.obtain().apply {
                    data = bundleOf(
                        AudioPluginViewService.MESSAGE_KEY_OPCODE to AudioPluginViewService.OPCODE_RESIZE,
                        AudioPluginViewService.MESSAGE_KEY_INSTANCE_ID to instanceId,
                        AudioPluginViewService.MESSAGE_KEY_WIDTH to width,
                        AudioPluginViewService.MESSAGE_KEY_HEIGHT to height
                    )
                }
                messenger.send(msg)
            } catch (e: RemoteException) {
                Log.w(TAG, "resize send failed", e)
                handleDisconnection("Resize failed: remote process lost")
            }
        }
    }

    private fun handleDisconnection(reason: String) {
        Log.w(TAG, "Remote UI disconnected: $reason")

        activeConnection?.unbind()
        activeConnection = null

        surfacePackage?.release()
        surfacePackage = null

        onDisconnected?.invoke(reason)
    }

    override fun close() {
        isDisposed = true

        activeConnection?.unbind()
        activeConnection = null

        surfacePackage?.release()
        surfacePackage = null

        messageHandlerThread.quitSafely()
    }

    private class GreenhouseSurfaceView(
        context: Context,
        private val host: GreenhouseSurfaceControlHost
    ) : SurfaceView(context) {

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            host.surfacePackage?.release()
            host.surfacePackage = null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    val from = rootSurfaceControl?.inputTransferToken
                    val to = host.surfacePackage?.inputTransferToken

                    if (from != null && to != null) {
                        val wm = context.getSystemService(WindowManager::class.java)
                        wm?.transferTouchGesture(from, to)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "transferTouchGesture failed: ${t.message}")
                }
            }

            return super.onTouchEvent(event)
        }
    }

    private class SafeServiceConnection(
        private val context: Context,
        private val onConnectedAction: (SafeServiceConnection) -> Unit,
        private val onDisconnectedAction: (String) -> Unit
    ) : ServiceConnection {

        var outgoingMessenger: Messenger? = null
        private val hasConnected = AtomicBoolean(false)
        private val isBound = AtomicBoolean(true)

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (hasConnected.compareAndSet(false, true)) {
                outgoingMessenger = Messenger(service)
                onConnectedAction(this)
            } else {
                Log.w(TAG, RECONNECT_SUPPRESSION_LOG_MSG)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            unbind()
            onDisconnectedAction(DISCONNECT_SERVICE_MSG)
        }

        override fun onBindingDied(name: ComponentName?) {
            unbind()
            onDisconnectedAction(DISCONNECT_BINDING_DIED_MSG)
        }

        override fun onNullBinding(name: ComponentName?) {
            unbind()
            onDisconnectedAction(DISCONNECT_NULL_BINDING_MSG)
        }

        fun unbind() {
            if (isBound.compareAndSet(true, false)) {
                outgoingMessenger = null

                try {
                    context.unbindService(this)
                } catch (t: Throwable) {
                    Log.w(TAG, "SafeServiceConnection unbind error: ${t.message}")
                }
            }
        }
    }
}
