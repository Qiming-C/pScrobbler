package com.arn.scrobble

import android.content.Context
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.arn.scrobble.charts.ChartsVM
import com.arn.scrobble.info.InfoFragment
import com.arn.scrobble.pref.MultiPreferences
import com.arn.scrobble.ui.ItemClickListener
import de.umass.lastfm.Album
import de.umass.lastfm.Artist
import de.umass.lastfm.ImageSize
import de.umass.lastfm.Track
import kotlinx.android.synthetic.main.content_search.*
import kotlinx.android.synthetic.main.content_search.search_term


class SearchFragment: Fragment() {
    private val pref by lazy { MultiPreferences(context!!) }
    private val viewModel by lazy { VMFactory.getVM(this, SearchVM::class.java) }
    private val chartsVM by lazy { VMFactory.getVM(this, ChartsVM::class.java) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.content_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        search_term.editText?.requestFocus()
        search_term.postDelayed({
            imm?.showSoftInput(search_term.editText, 0)
        }, 100)
        search_term.editText?.setOnEditorActionListener { textView, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadSearches(textView.text.toString())
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
                textView.clearFocus()
                true
            } else
                false
        }

        search_term.editText?.setOnClickListener {
            search_results_list.visibility = View.GONE
            search_history_list.visibility = View.VISIBLE
        }

        loadHistory()

        val historyAdapter = SearchHistoryAdapter(view)
        historyAdapter.viewModel = viewModel
        val historyItemClickListener = object : ItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                search_term.clearFocus()
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
                val term = viewModel.history[viewModel.history.size - position - 1]
                search_term.editText?.setText(term)
                search_term.editText?.setSelection(search_term.editText!!.text.toString().length)
                loadSearches(term)
            }
        }
        historyAdapter.clickListener = historyItemClickListener
        search_history_list.adapter = historyAdapter
        search_history_list.layoutManager = LinearLayoutManager(context)
        historyAdapter.populate()

        val resultsAdapter = SearchResultsAdapter(view)
        resultsAdapter.chartsVM = chartsVM
        val resultsItemClickListener = object : ItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                val item = resultsAdapter.getItem(position)
                if (item is Pair<*, *> && viewModel.searchResults.value != null) {
                    if (resultsAdapter.expandType != item.first as Int)
                        resultsAdapter.populate(viewModel.searchResults.value!!, item.first as Int, true)
                    else
                        resultsAdapter.populate(viewModel.searchResults.value!!, -1, true)
                } else {
                    when (item) {
                        is Artist -> {
                            val info = InfoFragment()
                            val b = Bundle()
                            b.putString(NLService.B_ARTIST, item.name)
                            info.arguments = b
                            info.show(activity!!.supportFragmentManager, null)
                        }
                        is Album -> {
                            val info = InfoFragment()
                            val b = Bundle()
                            b.putString(NLService.B_ARTIST, item.artist)
                            b.putString(NLService.B_ALBUM, item.name)
                            info.arguments = b
                            info.show(activity!!.supportFragmentManager, null)
                        }
                        is Track -> {
                            val info = InfoFragment()
                            val b = Bundle()
                            b.putString(NLService.B_ARTIST, item.artist)
                            b.putString(NLService.B_ALBUM, item.album)
                            b.putString(NLService.B_TITLE, item.name)
                            info.arguments = b
                            info.show(activity!!.supportFragmentManager, null)
                        }
                    }
                }
            }
        }

        val touchListener = View.OnTouchListener { p0, p1 ->
            if (search_term.editText!!.isFocused) {
                search_term.clearFocus()
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }
            false
        }

        search_results_list.setOnTouchListener(touchListener)
        search_history_list.setOnTouchListener(touchListener)

        resultsAdapter.clickListener = resultsItemClickListener
        search_results_list.adapter = resultsAdapter
        search_results_list.layoutManager = LinearLayoutManager(context)
        (search_results_list.itemAnimator as DefaultItemAnimator?)?.supportsChangeAnimations = false

        viewModel.searchResults.observe(viewLifecycleOwner) {
            it ?: return@observe
            if (!(it.artists.isEmpty() && it.albums.isEmpty() && it.tracks.isEmpty())) {
                viewModel.history.remove(it.term)
                viewModel.history += it.term
                historyAdapter.notifyDataSetChanged()
            }
            resultsAdapter.populate(it, -1, false)
        }

        chartsVM.info.observe(viewLifecycleOwner, {
            it ?: return@observe
            val imgUrl = when (val entry = it.second) {
                is Artist -> entry.getImageURL(ImageSize.EXTRALARGE) ?: ""
                is Album -> entry.getWebpImageURL(ImageSize.LARGE) ?: ""
                is Track -> entry.getWebpImageURL(ImageSize.LARGE) ?: ""
                else -> ""
            }
            resultsAdapter.setImg(it.first, imgUrl)
            chartsVM.removeInfoTask(it.first)
        })
    }

    override fun onStart() {
        super.onStart()
        Stuff.setTitle(activity, getString(R.string.search))
    }

    override fun onStop() {
        saveHistory()
        super.onStop()
    }

    private fun loadSearches(term: String) {
        search_history_list.visibility = View.GONE
        search_results_list.visibility = View.GONE
        search_progress.visibility = View.VISIBLE
        viewModel.loadSearches(term)
        chartsVM.imgMap.clear()
    }

    private fun loadHistory() {
        val historySet = pref.getStringSet(Stuff.PREF_ACTIVITY_SEARCH_HISTORY, setOf())
        val historyList = mutableListOf<Pair<Int,String>>()
        historySet.forEach {
            val parts = it.split('\n')
            historyList += parts[0].toInt() to parts[1]
        }
        historyList.sortBy { it.first }
        viewModel.history.clear()
        historyList.forEach {
            viewModel.history += it.second
        }
    }

    private fun saveHistory() {
        val historyPrefsSet = mutableSetOf<String>()
        viewModel.history.takeLast(7).forEachIndexed { i, it ->
            historyPrefsSet += "" + i + "\n" + it.replace(',', ' ')
        }
        pref.putStringSet(Stuff.PREF_ACTIVITY_SEARCH_HISTORY, historyPrefsSet)
    }
}