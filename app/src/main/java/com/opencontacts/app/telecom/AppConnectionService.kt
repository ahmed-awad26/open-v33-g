package com.opencontacts.app.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

class AppConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        return AppConnection().apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            setInitializing()
            setDialing()
            setActive()
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        return AppConnection().apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            setInitializing()
            setRinging()
        }
    }
}
