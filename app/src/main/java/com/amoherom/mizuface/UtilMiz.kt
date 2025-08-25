package com.amoherom.mizuface

import android.util.Log

class UtilMiz {

    companion object {
        val isDebug = false

        fun LogD(tag: String, out: String) {
            if (isDebug){
                Log.d(tag, out)
            }
        }

        fun LogE(tag: String, out: String) {
            if (isDebug){
                Log.e(tag, out)
            }
        }

        fun LogE(tag: String, out: String, tr: Throwable) {
            if (isDebug){
                Log.e(tag, out, tr)
            }
        }
    }

}