package com.amoherom.mizuface.fragment

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.amoherom.mizuface.BlenshapeMapper
import com.amoherom.mizuface.databinding.FaceBlendshapesResultBinding
import com.amoherom.mizuface.databinding.FragmentVtuberPcBinding
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Dictionary

class FaceBlendshapesResultAdapter :
    RecyclerView.Adapter<FaceBlendshapesResultAdapter.ViewHolder>() {
    companion object {
        private const val NO_VALUE = "--"
    }

    private var categories: MutableList<Category?> = MutableList(52) { null }
    private var blendshapes: MutableList<Pair<String, Float>> = BlenshapeMapper.blendshapeBundle


    var PC_IP = "192.168.1.3"
    var PC_PORT = "49983"


    fun updateResults(faceLandmarkerResult: FaceLandmarkerResult? = null) {
        categories = MutableList(53) { null }
        if (faceLandmarkerResult != null && faceLandmarkerResult.faceBlendshapes().isPresent) {
            val detectedFaceBlendshapes = faceLandmarkerResult.faceBlendshapes().get()
            val sortedCategories = detectedFaceBlendshapes[0].sortedByDescending { it.score() }
            val min = kotlin.math.min(sortedCategories.size, categories.size)
            for (i in 0 until min) {
                categories[i] = sortedCategories[i]
            }

            for (blenshape in categories){
                val index = blendshapes.indexOfFirst { it.first == blenshape?.categoryName() }
                if (index != -1) {
                    blendshapes[index] = Pair(blenshape?.categoryName() ?: "", blenshape?.score() ?: 0f)
                }
            }


        }



    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val binding = FaceBlendshapesResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        categories[position].let { category ->
            holder.bind(category?.categoryName(), category?.score())
        }
    }

    override fun getItemCount(): Int = categories.size

    inner class ViewHolder(private val binding: FaceBlendshapesResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(label: String?, score: Float?) {
            with(binding) {
                tvLabel.text = label ?: NO_VALUE
                tvScore.text = if (score != null) String.format(
                    "%.2f",
                    score
                ) else NO_VALUE
            }
        }
    }


}
