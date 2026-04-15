package com.amoherom.mizuface.fragment

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.amoherom.mizuface.databinding.IpPopupBinding

class IpPopupFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = IpPopupBinding.inflate(layoutInflater)
        binding.phoneIpLbl.text =
            arguments?.getString(ARG_PHONE_IP) ?: "Not Connected"
        binding.pcIpLbl.text =
            arguments?.getString(ARG_PC_IP) ?: "Not Connected"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()

        // Let ip_popup.xml's own rounded_bg show through; remove the default dialog background
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    companion object {
        const val TAG = "IpPopupFragment"
        private const val ARG_PHONE_IP = "phone_ip"
        private const val ARG_PC_IP   = "pc_ip"

        fun newInstance(phoneIp: String, pcIp: String) = IpPopupFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PHONE_IP, phoneIp)
                putString(ARG_PC_IP, pcIp)
            }
        }
    }

}