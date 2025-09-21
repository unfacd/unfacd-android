package org.thoughtcrime.securesms.stories.viewer.text

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.view.doOnNextLayout
import androidx.core.view.drawToBitmap
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.unfacd.android.R
import org.signal.core.util.DimensionUnit
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.mediapreview.MediaPreviewFragment
import org.thoughtcrime.securesms.stories.StoryTextPostView
import org.thoughtcrime.securesms.stories.viewer.page.StoryPost
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.FragmentDialogs.displayInDialogAboveAnchor
import org.thoughtcrime.securesms.util.fragments.requireListener
import org.thoughtcrime.securesms.util.visible

class StoryTextPostPreviewFragment : Fragment(R.layout.stories_text_post_preview_fragment) {

  companion object {
    private const val STORY_ID = "STORY_ID"

    fun create(content: StoryPost.Content.TextContent): Fragment {
      return StoryTextPostPreviewFragment().apply {
        arguments = Bundle().apply {
          putParcelable(MediaPreviewFragment.DATA_URI, content.uri)
          putLong(STORY_ID, content.recordId)
        }
      }
    }
  }

  private val viewModel: StoryTextPostViewModel by viewModels(
    factoryProducer = {
      StoryTextPostViewModel.Factory(requireArguments().getLong(STORY_ID), StoryTextPostRepository())
    }
  )

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val storyTextPostView: StoryTextPostView = view.findViewById(R.id.story_text_post)
    val storyTextThumb: ImageView = view.findViewById(R.id.story_text_post_shared_element_target)

    requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
      storyTextThumb.visible = true
      requireActivity().supportFinishAfterTransition()
    }

    viewModel.state.observe(viewLifecycleOwner) { state ->
      when (state.loadState) {
        StoryTextPostState.LoadState.INIT -> Unit
        StoryTextPostState.LoadState.LOADED -> {
          storyTextPostView.bindFromStoryTextPost(state.storyTextPost!!)
          storyTextPostView.bindLinkPreview(state.linkPreview)

          if (state.linkPreview != null) {
            storyTextPostView.setLinkPreviewClickListener {
              showLinkPreviewTooltip(it, state.linkPreview)
            }
          } else {
            storyTextPostView.setLinkPreviewClickListener(null)
          }
          loadPreview(storyTextThumb, storyTextPostView)
        }
        StoryTextPostState.LoadState.FAILED -> {
          requireActivity().supportStartPostponedEnterTransition()
          requireListener<MediaPreviewFragment.Events>().mediaNotAvailable()
        }
      }
    }
  }

  private fun loadPreview(storyTextThumb: ImageView, storyTextPreview: StoryTextPostView) {
    storyTextPreview.doOnNextLayout {
      if (it.isLaidOut) {
        actualLoadPreview(storyTextThumb, storyTextPreview)
      } else {
        it.post {
          actualLoadPreview(storyTextThumb, storyTextPreview)
        }
      }
    }
  }

  private fun actualLoadPreview(storyTextThumb: ImageView, storyTextPreview: StoryTextPostView) {
    storyTextThumb.setImageBitmap(storyTextPreview.drawToBitmap())
    requireActivity().supportStartPostponedEnterTransition()
    storyTextThumb.postDelayed({
      storyTextThumb.visible = false
    }, 200)
  }

  @SuppressLint("AlertDialogBuilderUsage")
  private fun showLinkPreviewTooltip(view: View, linkPreview: LinkPreview) {
    requireListener<Callback>().setIsDisplayingLinkPreviewTooltip(true)

    val contentView = LayoutInflater.from(requireContext()).inflate(R.layout.stories_link_popup, null, false)

    contentView.findViewById<TextView>(R.id.url).text = linkPreview.url
    contentView.setOnClickListener {
      CommunicationActions.openBrowserLink(requireContext(), linkPreview.url)
    }

    contentView.measure(
      View.MeasureSpec.makeMeasureSpec(DimensionUnit.DP.toPixels(275f).toInt(), View.MeasureSpec.EXACTLY),
      0
    )

    contentView.layout(0, 0, contentView.measuredWidth, contentView.measuredHeight)

    displayInDialogAboveAnchor(view, contentView, windowDim = 0f)
  }

  interface Callback {
    fun setIsDisplayingLinkPreviewTooltip(isDisplayingLinkPreviewTooltip: Boolean)
  }
}