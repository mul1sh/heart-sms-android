package xyz.klinker.messenger.fragment.message.send

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.net.Uri
import android.os.CountDownTimer
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import androidx.fragment.app.FragmentActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import xyz.klinker.messenger.R
import xyz.klinker.messenger.activity.MessengerActivity
import xyz.klinker.messenger.api.implementation.Account
import xyz.klinker.messenger.fragment.message.MessageListFragment
import xyz.klinker.messenger.fragment.conversation.ConversationListFragment
import xyz.klinker.messenger.shared.data.DataSource
import xyz.klinker.messenger.shared.data.MimeType
import xyz.klinker.messenger.shared.data.Settings
import xyz.klinker.messenger.shared.data.model.Message
import xyz.klinker.messenger.shared.data.pojo.KeyboardLayout
import xyz.klinker.messenger.shared.service.NewMessagesCheckService
import xyz.klinker.messenger.shared.service.jobs.MarkAsSentJob
import xyz.klinker.messenger.shared.util.*
import java.util.ArrayList

class SendMessageManager(private val fragment: MessageListFragment) {

    private val activity: FragmentActivity? by lazy { fragment.activity }

    private val argManager
        get() = fragment.argManager
    private val attachManager
        get() = fragment.attachManager
    private val messageLoader
        get() = fragment.messageLoader
    
    internal val messageEntry: EditText by lazy { fragment.rootView!!.findViewById<View>(R.id.message_entry) as EditText }
    private val sendProgress: ProgressBar? by lazy { fragment.rootView?.findViewById<View>(R.id.send_progress) as ProgressBar? }
    private val attach: View by lazy { fragment.rootView!!.findViewById<View>(R.id.attach) }
    private val send: FloatingActionButton by lazy { fragment.rootView!!.findViewById<View>(R.id.send) as FloatingActionButton }
    private val selectedImageCount: View by lazy { fragment.rootView!!.findViewById<View>(R.id.selected_images) }

    private var delayedTimer: CountDownTimer? = null
    private val delayedSendingHandler: Handler by lazy { Handler() }
    
    fun initSendbar() {
        delayedSendingHandler.post {  } // initialize

        KeyboardLayoutHelper.applyLayout(messageEntry)
        messageEntry.textSize = Settings.largeFont.toFloat()

        messageEntry.setOnEditorActionListener { _, actionId, _ ->
            var handled = false

            if (actionId == EditorInfo.IME_ACTION_SEND) {
                requestPermissionThenSend()
                handled = true
            }

            handled
        }

        messageEntry.setOnClickListener {
            val attachLayout: View? = fragment.rootView!!.findViewById(R.id.attach_layout)
            if (attachLayout?.visibility == View.VISIBLE) {
                attach.isSoundEffectsEnabled = false
                attach.performClick()
                attach.isSoundEffectsEnabled = true
            }
        }

        val sendOnEnter = Settings.keyboardLayout === KeyboardLayout.SEND
        messageEntry.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
            override fun afterTextChanged(editable: Editable) {}
            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                fragment.counterCalculator.updateCounterText()
                if (sendOnEnter && charSequence.isNotEmpty()) {
                    val lastKey = charSequence[charSequence.length - 1]
                    if (lastKey == '\n') {
                        requestPermissionThenSend()
                    }
                }

                if (sendProgress?.visibility == View.VISIBLE) {
                    val message = messageEntry.text.toString().trim { it <= ' ' }
                    val uris = ArrayList<Uri>()

                    if (attachManager.selectedImageUris.size > 0) {
                        attachManager.selectedImageUris.mapTo(uris) { Uri.parse(it) }
                    } else if (attachManager.attachedUri != null) {
                        uris.add(attachManager.attachedUri!!)
                    }

                    if (message.isEmpty() && uris.isEmpty()) {
                        // cancel delayed sending for empty message
                        changeDelayedSendingComponents(false)
                    } else {
                        // reset the delayed sending, if the user makes a change to the entered text
                        changeDelayedSendingComponents(true)
                        delayedSendingHandler.postDelayed({ sendMessage(uris, false) }, Settings.delayedSendingTimeout)
                    }
                }
            }

        })

        val accent = fragment.argManager.colorAccent
        sendProgress?.progressTintList = ColorStateList.valueOf(accent)
        sendProgress?.progressBackgroundTintList = ColorStateList.valueOf(accent)
        sendProgress?.progressTintMode = PorterDuff.Mode.SRC_IN
        sendProgress?.progressTintMode = PorterDuff.Mode.SRC_IN
        send.setOnClickListener{ requestPermissionThenSend() }

        send.setOnLongClickListener {
            val sig = Settings.signature
            val signature = sig != null && !sig.isEmpty()
            val delayedSending = Settings.delayedSendingTimeout != 0L

            when {
                signature && delayedSending -> {
                    AlertDialog.Builder(activity!!)
                            .setItems(R.array.send_button_signature_delay) { _, position ->
                                when (position) {
                                    0 -> scheduleMessage()
                                    1 -> sendMessageOnFragmentClosed()
                                    2 -> requestPermissionThenSend(true)
                                }
                            }.show()
                }
                !signature && delayedSending -> {
                    AlertDialog.Builder(activity!!)
                            .setItems(R.array.send_button_no_signature_delay) { _, position ->
                                when (position) {
                                    0 -> scheduleMessage()
                                    1 -> sendMessageOnFragmentClosed()
                                }
                            }.show()
                }
                signature && !delayedSending -> {
                    AlertDialog.Builder(activity!!)
                            .setItems(R.array.send_button_signature_no_delay) { _, position ->
                                when (position) {
                                    0 -> scheduleMessage()
                                    1 -> requestPermissionThenSend(true)
                                }
                            }.show()
                }
                else -> {
                    AlertDialog.Builder(activity!!)
                            .setMessage(R.string.send_as_scheduled_message_question)
                            .setPositiveButton(android.R.string.yes) { _, _ -> scheduleMessage() }
                            .setNegativeButton(android.R.string.no) { _, _ -> }
                            .show()
                }
            }

            false
        }
    }

    private fun scheduleMessage() {
        (activity as? MessengerActivity)?.navController?.drawerItemClicked(R.id.menu_conversation_schedule)

    }

    fun sendDelayedMessage() {
        if (sendProgress?.visibility == View.VISIBLE) {
            sendMessageOnFragmentClosed()
        }
    }

    fun sendOnFragmentDestroyed() {
        if (sendProgress?.visibility == View.VISIBLE) {
            sendMessageOnFragmentClosed()
        }
    }

    fun resendMessage(originalMessageId: Long, text: String) {
        if (activity == null) {
            return
        }

        DataSource.deleteMessage(activity!!, originalMessageId)

        fragment.messageLoader.messageLoadedCount--
        fragment.messageLoader.loadMessages(false)

        Handler().postDelayed({
            messageEntry.setText(text)
            requestPermissionThenSend()
        }, 300)
    }

    private fun sendMessageOnFragmentClosed() {
        sendProgress?.visibility = View.GONE
        delayedSendingHandler.removeCallbacksAndMessages(null)

        val uris = ArrayList<Uri>()

        if (attachManager.selectedImageUris.size > 0) {
            attachManager.selectedImageUris.mapTo(uris) { Uri.parse(it) }
        } else if (attachManager.attachedUri != null) {
            uris.add(attachManager.attachedUri!!)
        }

        sendMessage(uris)
        messageEntry.setText("")
    }

    private fun requestPermissionThenSend() {
        requestPermissionThenSend(false)
    }

    fun requestPermissionThenSend(forceNoSignature: Boolean, delayedSendingTimeout: Long = Settings.delayedSendingTimeout) {
        if (activity == null) {
            return
        }

        // finding the message and URIs is also done in the onBackPressed method.
        val message = messageEntry.text.toString().trim { it <= ' ' }
        val uris = ArrayList<Uri>()

        if (attachManager.selectedImageUris.size > 0) {
            attachManager.selectedImageUris.distinctBy { it }.mapTo(uris) { Uri.parse(it) }
        } else if (attachManager.attachedUri != null) {
            uris.add(attachManager.attachedUri!!)
        }

        if ((!Account.exists() || Account.primary) && PermissionsUtils.checkRequestMainPermissions(activity!!)) {
            PermissionsUtils.startMainPermissionRequest(activity!!)
        } else if (Account.primary && !PermissionsUtils.isDefaultSmsApp(activity!!)) {
            PermissionsUtils.setDefaultSmsApp(activity!!)
        } else if (message.isNotEmpty() || uris.size > 0) {
            if (delayedSendingTimeout != 0L) {
                changeDelayedSendingComponents(true, delayedSendingTimeout)
            }

            delayedSendingHandler.postDelayed({ sendMessage(uris, forceNoSignature) }, delayedSendingTimeout)
        }
    }

    private fun changeDelayedSendingComponents(start: Boolean, delayedSendingTimeout: Long = Settings.delayedSendingTimeout) {
        delayedSendingHandler.removeCallbacksAndMessages(null)
        delayedTimer?.cancel()

        if (!start) {
            sendProgress?.progress = 0
            sendProgress?.visibility = View.INVISIBLE
            send.setImageResource(R.drawable.ic_send)
            send.setOnClickListener { requestPermissionThenSend() }
        } else {
            sendProgress?.isIndeterminate = false
            sendProgress?.visibility = View.VISIBLE
            send.setImageResource(R.drawable.ic_close)
            send.setOnClickListener { changeDelayedSendingComponents(false) }

            sendProgress?.max = delayedSendingTimeout.toInt() / 10

            delayedTimer = object : CountDownTimer(delayedSendingTimeout, 10) {
                override fun onFinish() {}

                override fun onTick(millisUntilFinished: Long) {
                    sendProgress?.progress = (delayedSendingTimeout - millisUntilFinished).toInt() / 10
                }
            }.start()
        }
    }

    private fun sendMessage(uris: List<Uri>) {
        sendMessage(uris, false)
    }

    private fun sendMessage(uris: List<Uri>, forceNoSignature: Boolean) {
        val activity = activity ?: return

        NewMessagesCheckService.writeLastRun(activity)
        changeDelayedSendingComponents(false)

        val message = messageEntry.text.toString().trim { it <= ' ' }
        val mimeType = if (attachManager.attachedMimeType != null)
            attachManager.attachedMimeType else MimeType.TEXT_PLAIN

        if ((message.isNotEmpty() || uris.isNotEmpty())) {
            val conversation = DataSource.getConversation(activity, argManager.conversationId)

            val m = Message()
            m.conversationId = argManager.conversationId
            m.type = Message.TYPE_SENDING
            m.data = message
            m.timestamp = TimeUtils.now
            m.mimeType = MimeType.TEXT_PLAIN
            m.read = true
            m.seen = true
            m.from = null
            m.color = null
            m.simPhoneNumber = if (conversation?.simSubscriptionId != null)
                DualSimUtils.getPhoneNumberFromSimSubscription(conversation.simSubscriptionId!!)
            else
                null
            m.sentDeviceId = if (Account.exists()) java.lang.Long.parseLong(Account.deviceId!!) else -1L

            if (messageLoader.adapter != null && messageLoader.adapter!!.getItemViewType(0) == Message.TYPE_INFO) {
                DataSource.deleteMessage(activity, messageLoader.adapter!!.getItemId(0))
            }

            attachManager.clearAttachedData()
            attachManager.selectedImageUris.clear()
            selectedImageCount.visibility = View.GONE
            
            DataSource.deleteDrafts(activity, argManager.conversationId)
            messageEntry.text = null

            val fragment = activity.supportFragmentManager.findFragmentById(R.id.conversation_list_container)

            if (fragment != null && fragment is ConversationListFragment) {
                val originalMime = m.mimeType
                if (uris.isNotEmpty()) {
                    m.mimeType = mimeType
                }

                fragment.notifyOfSentMessage(m)
                m.mimeType = originalMime
            }

            var loadMessages = false

            if (message.isNotEmpty()) {
                DataSource.insertMessage(activity, m, m.conversationId)
                loadMessages = true
            }

            if (uris.isNotEmpty()) {
                m.data = uris[0].toString()
                m.mimeType = mimeType

                if (m.id != 0L) {
                    m.id = 0
                }

                m.id = DataSource.insertMessage(activity, m, m.conversationId, true)

                loadMessages = true
            }

            val copiedUris = mutableListOf<Uri>()
            copiedUris.addAll(uris)

            val messageId = m.id
            Thread {
                val imageUri = SendUtils(conversation?.simSubscriptionId)
                        .setForceNoSignature(forceNoSignature)
                        .send(activity, message, argManager.phoneNumbers,
                                if (copiedUris.isNotEmpty()) copiedUris[0] else null, mimeType)
                MarkAsSentJob.scheduleNextRun(activity, messageId)

                if (imageUri != null) {
                    DataSource.updateMessageData(activity, messageId, imageUri.toString())
                }
            }.start()

            if (copiedUris.size > 1) {
                for (i in 1 until copiedUris.size) {
                    val sendUri = copiedUris[i]
                    val imageMessage = Message()
                    imageMessage.conversationId = argManager.conversationId
                    imageMessage.type = Message.TYPE_SENDING
                    imageMessage.data = sendUri.toString()
                    imageMessage.timestamp = TimeUtils.now
                    imageMessage.mimeType = mimeType
                    imageMessage.read = true
                    imageMessage.seen = true
                    imageMessage.from = null
                    imageMessage.color = null
                    imageMessage.simPhoneNumber = if (conversation?.simSubscriptionId != null)
                        DualSimUtils.getPhoneNumberFromSimSubscription(conversation.simSubscriptionId!!)
                    else null
                    imageMessage.sentDeviceId = if (Account.exists()) java.lang.Long.parseLong(Account.deviceId!!) else -1L

                    Thread {
                        val newId = DataSource.insertMessage(activity, imageMessage, imageMessage.conversationId, true)
                        val imageUri = SendUtils(conversation?.simSubscriptionId)
                                .setForceNoSignature(forceNoSignature)
                                .send(activity, /*message*/ "", argManager.phoneNumbers,
                                        sendUri, mimeType)
                        MarkAsSentJob.scheduleNextRun(activity, newId)

                        if (imageUri != null) {
                            DataSource.updateMessageData(activity, newId, imageUri.toString())
                        }
                    }.start()
                }

                loadMessages = true
            }

            if (loadMessages) {
                this.fragment.loadMessages(true)
            }

            AudioWrapper(activity, R.raw.message_ping).play()
            this.fragment.notificationManager.dismissOnMessageSent()
        }
    }
}