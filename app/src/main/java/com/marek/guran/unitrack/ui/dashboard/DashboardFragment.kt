package com.marek.guran.unitrack.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.marek.guran.unitrack.databinding.FragmentDashboardBinding

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dashboardViewModel =
            ViewModelProvider(this).get(DashboardViewModel::class.java)

        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textDashboard
        dashboardViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        animateEntrance()
    }

    private fun animateEntrance() {
        val root = binding.root
        // Find the scroll view and its container by traversing the view hierarchy safely
        val scrollView = (root as? ViewGroup)?.let { parent ->
            (0 until parent.childCount).firstNotNullOfOrNull { i ->
                parent.getChildAt(i) as? ViewGroup
            }
        } ?: return
        val container = (0 until scrollView.childCount).firstNotNullOfOrNull { i ->
            scrollView.getChildAt(i) as? ViewGroup
        } ?: return

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = 40f
            child.scaleX = 0.97f
            child.scaleY = 0.97f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setStartDelay((i * 100 + 100).toLong())
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}