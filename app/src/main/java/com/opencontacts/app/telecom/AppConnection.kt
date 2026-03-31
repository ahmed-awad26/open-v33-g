package com.opencontacts.app.telecom

import android.telecom.Connection
import android.telecom.DisconnectCause

class AppConnection : Connection() {

    override fun onAnswer() {
        setActive()
    }

    override fun onDisconnect() {
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onHold() {
        setOnHold()
    }

    override fun onUnhold() {
        setActive()
    }
}
