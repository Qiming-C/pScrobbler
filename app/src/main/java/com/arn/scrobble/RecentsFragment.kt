package com.arn.scrobble

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
import com.arn.scrobble.info.InfoFragment
import com.arn.scrobble.pref.MultiPreferences
import com.arn.scrobble.ui.*
import com.robinhood.spark.SparkView
import com.robinhood.spark.animation.MorphSparkAnimator
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import de.umass.lastfm.ImageSize
import de.umass.lastfm.Track
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_recents.*
import kotlinx.android.synthetic.main.coordinator_main.*
import kotlinx.android.synthetic.main.coordinator_main.view.*
import kotlinx.android.synthetic.main.list_item_recents.view.*
import java.util.*
import kotlin.math.max


/**
 * Created by arn on 09/07/2017.
 */

open class RecentsFragment : Fragment(), ItemClickListener, FocusChangeListener, RecentsAdapter.SetHeroTrigger {
    private lateinit var adapter: RecentsAdapter
    private lateinit var appPrefs: SharedPreferences
    private var timedRefresh = Runnable {
        if (viewModel.toTime == 0L)
            loadRecents(1)
        val ps = activity?.coordinator?.paddingStart
        if (ps != null && ps > 0)
            LFMRequester(context!!).getDrawerInfo().asAsyncTask()
        lastRefreshTime = System.currentTimeMillis()
    }
    private var lastRefreshTime = System.currentTimeMillis()
    private val refreshHandler by lazy { Handler(Looper.getMainLooper()) }
    private val username: String?
        get() = parentFragment?.arguments?.getString(Stuff.ARG_USERNAME)
    private lateinit var viewModel: TracksVM
    private lateinit var animSet: AnimatorSet
    private var smoothScroller: LinearSmoothScroller? = null
    open var isShowingLoves = false

    private var colorPrimDark = 0
    private var colorLightWhite = 0
    private var colorMutedDark = 0
    private var colorMutedBlack = 0

    private val editReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent) {
            val extras = i.extras!!
            adapter.editTrack(extras.getString(NLService.B_ARTIST)!!,
                    extras.getString(NLService.B_ALBUM)!!,
                    extras.getString(NLService.B_TITLE)!!,
                    extras.getLong(NLService.B_TIME))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.content_recents, container, false)
    }

    override fun onResume() {
        super.onResume()
        if (recents_list?.adapter == null)
            postInit()
        else {
            val holder = recents_list.findViewHolderForAdapterPosition(viewModel.selectedPos)
            (holder as? RecentsAdapter.VHTrack)?.setSelected(true)
            if (!isShowingLoves &&
                    System.currentTimeMillis() - lastRefreshTime >= Stuff.RECENTS_REFRESH_INTERVAL &&
                    viewModel.page == 1)
                timedRefresh.run()
        }
        if (isShowingLoves) {
            activity?.hero_calendar?.visibility = View.INVISIBLE
            activity?.hero_calendar?.isEnabled = false
        } else if (!Main.isTV){
            activity?.hero_calendar?.visibility = View.VISIBLE
            activity?.hero_calendar?.isEnabled = true
        }
        activity?.hero_calendar?.tag = !isShowingLoves
    }

    override fun onPause() {
        if (isShowingLoves)
            try {
                adapter.removeHandlerCallbacks()
            }catch (e: Exception){}
        super.onPause()
    }

    private fun postInit() {
        val activity = activity as Main? ?: return
        activity.toolbar.title = null
        Stuff.setAppBarHeight(activity)

        val llm = LinearLayoutManager(context!!)
        recents_list.layoutManager = llm

        viewModel = VMFactory.getVM(this, TracksVM::class.java)
        viewModel.username = username
        adapter = RecentsAdapter(view!!)
        adapter.viewModel = viewModel
        adapter.isShowingLoves = isShowingLoves
        adapter.setStatusHeader()

        animSet = AnimatorSet()
        animSet.addListener(object : Animator.AnimatorListener{
            override fun onAnimationRepeat(p0: Animator?) {}

            override fun onAnimationEnd(p0: Animator?) {
                lastColorLightWhite = colorLightWhite
                lastColorMutedDark = colorMutedDark
                lastColorMutedBlack = colorMutedBlack
            }

            override fun onAnimationCancel(p0: Animator?) {}

            override fun onAnimationStart(p0: Animator?) {}

        })

        val itemDecor = DividerItemDecoration(context!!, VERTICAL)
        itemDecor.setDrawable(ColorDrawable(ContextCompat.getColor(context!!, R.color.lightDivider)))
        recents_list.addItemDecoration(itemDecor)
        recents_list.addItemDecoration(SimpleHeaderDecoration(0, Stuff.dp2px(25, context!!)))
        Stuff.setProgressCircleColor(recents_swipe_refresh)
        recents_swipe_refresh.setOnRefreshListener {
            viewModel.toTime = 0
            loadRecents(1)
        }
        recents_swipe_refresh.isRefreshing = false
        recents_list.adapter = adapter
        (recents_list.itemAnimator as DefaultItemAnimator?)?.supportsChangeAnimations = false

        val loadMoreListener = object : EndlessRecyclerViewScrollListener(llm) {
            override fun onLoadMore(page: Int, totalItemsCount: Int, view: RecyclerView) {
                loadRecents(page)
            }
        }
        loadMoreListener.currentPage = viewModel.page

        recents_list.addOnScrollListener(loadMoreListener)
        adapter.loadMoreListener = loadMoreListener
        adapter.itemClickListener = this
        adapter.focusChangeListener = this
        adapter.setHeroListener = this
        appPrefs = context!!.getSharedPreferences(Stuff.ACTIVITY_PREFS, Context.MODE_PRIVATE)

        val sparkline = activity.sparkline
        if (sparkline.adapter == null) { // not inited
            sparkline.sparkAnimator = MorphSparkAnimator()
            sparkline.adapter = SparkLineAdapter()
        }
        viewModel.tracksReceiver.observe(viewLifecycleOwner, {
            it ?: return@observe
            synchronized(viewModel.tracks) {
                val oldList = mutableListOf<Track>()
                oldList.addAll(viewModel.tracks)
                viewModel.totalPages = max(1, it.totalPages) //dont let totalpages be 0
                if (it.page == 1)
                    viewModel.tracks.clear()
                it.forEach { track ->
                    if (viewModel.deletedTracksStringSet.contains(it.toString()))
                        return@forEach
                    if (!track.isNowPlaying || it.page == 1)
                        viewModel.tracks.add(track)
                    if (isShowingLoves && viewModel.imgMap[Stuff.genHashCode(track.artist, track.name)] != null)
                        track.imageUrlsMap = viewModel.imgMap[Stuff.genHashCode(track.artist, track.name)]
                }
                adapter.populate(oldList)
            }
            if (viewModel.page != it.page && Main.isTV)
                loadRecents(1, true)
            loadMoreListener.currentPage = it.page

            if (!viewModel.loadedNw) {
                loadRecents(1)
                toggleGraphDetails(activity.sparkline, true)
                viewModel.loadedNw = true
            } else if (it.page == 1 && !isShowingLoves) {
                if (username == null)
                    viewModel.loadPending(2, false)
                refreshHandler.removeCallbacks(timedRefresh)
                refreshHandler.postDelayed(timedRefresh, Stuff.RECENTS_REFRESH_INTERVAL)
            }
        })
        viewModel.loadHero(null)
                .observe(viewLifecycleOwner, {
                    it ?: return@observe
                    setGraph(it[0])
                })
        if (isShowingLoves)
            viewModel.trackInfo.observe(viewLifecycleOwner, {
                it ?: return@observe
                adapter.setImg(it.first, it.second?.imageUrlsMap)
            })
        else if (username == null)
            viewModel.loadPending(2, !activity.pendingSubmitAttempted)
                    .observe(viewLifecycleOwner, {
                        it ?: return@observe
                        activity.pendingSubmitAttempted = true
                        adapter.setPending(activity.supportFragmentManager, it)
                    })

        sparkline.setOnClickListener{
            toggleGraphDetails(it as SparkView)
        }

        activity.hero_share.setOnClickListener{
            val track = activity.hero_img?.tag
            if (track is Track) {
                var shareText: String
                if (username == null)
                    shareText = getString(R.string.share_text,
                            track.artist + " - " + track.name, Stuff.myRelativeTime(context!!, track.playedWhen))
                else
                    shareText = getString(R.string.share_text_username, username,
                            track.artist + " - " + track.name, Stuff.myRelativeTime(context!!, track.playedWhen))
                shareText += "\n\n" +appPrefs.getString(Stuff.PREF_ACTIVITY_SHARE_SIG,
                        getString(R.string.share_sig, getString(R.string.share_link)))
                val i = Intent(Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_SUBJECT, shareText)
                i.putExtra(Intent.EXTRA_TEXT, shareText)
                startActivity(Intent.createChooser(i, getString(R.string.share_this_song)))
            }
        }

        activity.hero_info.setOnClickListener {
            val t = activity.hero_img?.tag
            if (t is Track) {
                val info = InfoFragment()
                val b = Bundle()
                b.putString(NLService.B_ARTIST, t.artist)
                if (!t.album.isNullOrEmpty())
                    b.putString(NLService.B_ALBUM, t.album)
                b.putString(NLService.B_TITLE, t.name)
                b.putString(Stuff.ARG_USERNAME, username)
                info.arguments = b
                info.show(activity.supportFragmentManager, null)
            }
        }
        activity.hero_play.setOnClickListener {
            val t =  activity.hero_img?.tag
            if (t is Track)
                Stuff.launchSearchIntent(t.artist, t.name, context!!)
        }

        activity.hero_calendar.setOnClickListener {
            val cal = Calendar.getInstance()
            if (viewModel.toTime > 0) {
                cal.time = Date(viewModel.toTime)
            }
            val dpd = DatePickerDialog(context!!, R.style.DarkDialog, {datePicker, year, month, dayOfMonth ->
                datePicker ?: return@DatePickerDialog
                cal.set(year, month, dayOfMonth, 23, 59, 59)
                viewModel.toTime = cal.timeInMillis
                loadRecents(1, true)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            dpd.datePicker.maxDate = System.currentTimeMillis()
            dpd.datePicker.minDate = if (username == null)
                appPrefs.getLong(Stuff.PREF_ACTIVITY_SCROBBLING_SINCE, 0)
            else
                parentFragment!!.arguments?.getLong(Stuff.ARG_REGISTERED_TIME, 0) ?: 0
            dpd.setTitle(R.string.past_scrobbles)
            dpd.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.reset)) { dialogInterface, i ->
                viewModel.toTime = 0
                loadRecents(1, true)
            }
            dpd.show()
        }
        if (viewModel.tracks.isEmpty())
            loadRecents(1)
        else
            adapter.populate(viewModel.tracks)
    }

    private fun setGraph(pointsStr: String?) {
        val frame = activity?.sparkline_frame  ?: return
        if (pointsStr.isNullOrBlank()) {
            frame.visibility = View.INVISIBLE
        } else {
            val points = mutableListOf<Int>()
            pointsStr.split(", ")
                    .forEach {
                        points.add(it.toInt())
                    }
            if (points.isEmpty())
                frame.visibility = View.INVISIBLE
            else {
                frame.visibility = View.VISIBLE
                val sparklineAdapter = activity!!.sparkline.adapter as SparkLineAdapter
                sparklineAdapter.setData(points)
                val max = sparklineAdapter.max()
                val min = sparklineAdapter.min()
                activity!!.sparkline_tick_top.text = Stuff.humanReadableNum(max)

                if (max != min)
                    activity!!.sparkline_tick_bottom.text = Stuff.humanReadableNum(min)
                else
                    activity!!.sparkline_tick_bottom.text = ""
            }

        }
    }

    private fun toggleGraphDetails(sparkline: SparkView, init: Boolean = false){
        val activity = activity ?: return
        var show = appPrefs.getBoolean(Stuff.PREF_ACTIVITY_GRAPH_DETAILS, true)
        if (!init)
            show = !show

        if (show) {
            sparkline.setPadding(2*resources.getDimensionPixelSize(R.dimen.graph_margin_details),
                    0,0,
                    resources.getDimensionPixelSize(R.dimen.graph_margin_details))
            activity.sparkline_horizontal_label.visibility = View.VISIBLE
            activity.sparkline_tick_top.visibility = View.VISIBLE
            activity.sparkline_tick_bottom.visibility = View.VISIBLE
        } else {
            sparkline.setPadding(0,0,0,0)
            activity.sparkline_horizontal_label.visibility = View.GONE
            activity.sparkline_tick_top.visibility = View.GONE
            activity.sparkline_tick_bottom.visibility = View.GONE
        }
        appPrefs.edit()
                .putBoolean(Stuff.PREF_ACTIVITY_GRAPH_DETAILS, show)
                .apply()
    }

    private fun loadRecents(page: Int, force:Boolean = false): Boolean {
        recents_list ?: return false

        if (page <= viewModel.totalPages) {
            val firstVisible = ((recents_list.layoutManager ?: return false) as LinearLayoutManager)
                    .findFirstVisibleItemPosition()
            if (force || (page == 1 && firstVisible < 5) || page > 1) {
                if (isShowingLoves)
                    viewModel.loadLoves(page)
                else
                    viewModel.loadRecents(page)
            }
            if (adapter.itemCount == 0 || page > 1)
                adapter.setLoading(true)
        } else {
            adapter.setLoading(false)
            adapter.loadMoreListener.isAllPagesLoaded = true
            return false
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        if(!isShowingLoves)
            context?.registerReceiver(editReceiver, IntentFilter(NLService.iEDITED))
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(timedRefresh)
        try {
            context?.unregisterReceiver(editReceiver)
        } catch (e: IllegalArgumentException) {}

        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isVisible)
            Stuff.setAppBarHeight(activity!!)
    }

    override fun onSetHero(position: Int, track: Track, fullSize: Boolean) {
        val ctl = activity?.ctl ?: return
        val hero = ctl.hero_img

        //check diff
        val oldTrack = hero.tag as Track?

        ctl.hero_title.text = track.name
        //TODO: check
        hero.tag = track
        val imgUrl = if(fullSize)
            track.getWebpImageURL(ImageSize.EXTRALARGE)?.replace("300x300", "600x600")
        else
            track.getWebpImageURL(ImageSize.LARGE)

        if (!fullSize &&
                (oldTrack?.artist != track.artist || oldTrack?.album != track.album || oldTrack?.name != track.name)){
            viewModel.loadHero(track.url)
        }

        if (!imgUrl.isNullOrEmpty() && imgUrl == oldTrack?.getWebpImageURL(ImageSize.LARGE)) {
            if ((activity?.ctl?.contentScrim as? ColorDrawable?)?.color != lastColorMutedBlack)
                setPaletteColors()
            return
        }
        //load img, animate colors
        if (!imgUrl.isNullOrEmpty()) {
            val req = Picasso.get()
                    .load(imgUrl)
                    .error(R.drawable.vd_wave)

            if (fullSize)
                req.placeholder(hero.drawable)
            else
                req.noPlaceholder()
            req.into(hero, object : Callback {
                override fun onSuccess() {
//                    hero.background = null
                    hero.clearColorFilter()
                    setPaletteColors()
                    if (!fullSize)
                        onSetHero(position, track, true)
                }

                override fun onError(e: Exception) {
                    if (!fullSize)
                        onSetHero(position, track, true)
                    Stuff.log("Picasso err: $e")
                }
            })
        } else {
            Picasso.get().cancelRequest(hero)
            val color = Stuff.getMatColor(hero.context, "500", Stuff.genHashCode(track.artist, track.name).toLong())
            hero.setColorFilter(color)
            hero.setImageResource(R.drawable.vd_wave)
            setPaletteColors(color)
        }
    }

    private fun setPaletteColors(oneColor: Int? = null){
        val activity = activity ?: return
        val ctl = activity.ctl
        val content = recents_swipe_refresh ?: return

        fun set(palette: Palette?) {
            palette ?: return
            context ?: return

            colorPrimDark = palette.getDominantColor(Color.WHITE)
            if (!Stuff.isDark(colorPrimDark))
                colorPrimDark = palette.getDarkVibrantColor(ContextCompat.getColor(context!!, R.color.colorPrimary))
            colorLightWhite = palette.getLightMutedColor(ContextCompat.getColor(context!!, android.R.color.primary_text_dark))
            colorMutedDark = palette.getDarkMutedColor(ContextCompat.getColor(context!!, R.color.colorPrimary))
            colorMutedBlack = palette.getDarkMutedColor(Color.BLACK)

            ctl.setContentScrimColor(colorMutedBlack)
            ctl.hero_title.setTextColor(colorLightWhite)

            val contentBgFrom = (content.background as ColorDrawable).color
            val contentBgAnimator = ObjectAnimator.ofArgb(content, "backgroundColor", contentBgFrom, colorMutedBlack)
            val navBgAnimator = ObjectAnimator.ofArgb(activity.nav_view, "backgroundColor", contentBgFrom, colorMutedBlack)
            val shareBgAnimator = ObjectAnimator.ofArgb(activity.hero_share, "colorFilter", lastColorLightWhite, colorLightWhite)
            val calendarColorAnimator = ObjectAnimator.ofArgb(activity.hero_calendar, "colorFilter", lastColorLightWhite, colorLightWhite)
            val infoBgAnimator = ObjectAnimator.ofArgb(activity.hero_info, "colorFilter", lastColorLightWhite, colorLightWhite)
            val searchBgAnimator = ObjectAnimator.ofArgb(activity.hero_play, "colorFilter", lastColorLightWhite, colorLightWhite)
            val sparklineTickTopAnimator = ObjectAnimator.ofArgb(activity.sparkline_tick_top, "textColor", lastColorLightWhite, colorLightWhite)
            val sparklineTickBottomAnimator = ObjectAnimator.ofArgb(activity.sparkline_tick_bottom, "textColor", lastColorLightWhite, colorLightWhite)
            val sparklineHorizontalLabel = ObjectAnimator.ofArgb(activity.sparkline_horizontal_label, "textColor", lastColorLightWhite, colorLightWhite)
            val sparklineAnimator = ObjectAnimator.ofArgb(activity.sparkline, "lineColor", lastColorLightWhite, colorLightWhite)
            val navbarBgAnimator = ValueAnimator.ofArgb(contentBgFrom, colorMutedBlack)
            navbarBgAnimator.addUpdateListener{
                activity.window.navigationBarColor = it.animatedValue as Int
            }

            val animSetList = mutableListOf(contentBgAnimator,
                    calendarColorAnimator, shareBgAnimator,
                    searchBgAnimator, infoBgAnimator,
                    navbarBgAnimator, sparklineAnimator, sparklineHorizontalLabel,
                    sparklineTickBottomAnimator, sparklineTickTopAnimator)
            if (activity.coordinator.paddingStart > 0)
                animSetList.add(navBgAnimator)

            for (i in 0..ctl.toolbar.childCount){
                val child = ctl.toolbar.getChildAt(i)
                if (child is ImageButton){

                    val bgColor = if (colorPrimDark != colorLightWhite)
                        colorPrimDark
                    else
                        colorMutedDark

                    (child.drawable as ShadowDrawerArrowDrawable).setColors(colorLightWhite, bgColor)
                    break
                }
            }
            animSet.cancel()
            animSet.playTogether(animSetList.toList())
            animSet.interpolator = AccelerateDecelerateInterpolator()
            animSet.duration = 1500
            animSet.start()
        }

        val d = ctl.hero_img.drawable
        if (oneColor != null) {
            val swatch = Palette.Swatch(oneColor, 1)
            val palette = Palette.from(listOf(swatch))
            set(palette)
        } else if (d is BitmapDrawable && d.bitmap != null)
            Palette.from(d.bitmap).generate{ set(it) }
    }

    override fun onItemClick (view: View, position: Int) {
        val item = adapter.getItem(position) ?: return
        if (item !is Track) {
            if (view.id == R.id.recents_menu)
                PendingMenu.openPendingPopupMenu((view.parent as ViewGroup).date_frame, item,
                        {
                            viewModel.loadPending(2, false)
                        }
                )
            return
        }
        when (view.id) {
            R.id.recents_menu -> openTrackPopupMenu((view.parent as ViewGroup).date_frame, item)
            R.id.recents_img_overlay -> {
                loveToggle(view, item)
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            else -> {
                val lastClickedPos = viewModel.selectedPos
                viewModel.selectedPos = position
                val lastHolder = recents_list.findViewHolderForAdapterPosition(lastClickedPos)
                val curHolder = recents_list.findViewHolderForAdapterPosition(position)
                if (lastHolder is RecentsAdapter.VHTrack? && curHolder is RecentsAdapter.VHTrack) {
                    if (lastClickedPos != position)
                        lastHolder?.setSelected(false, item)
                    curHolder.setSelected(true, item)
                }

                val activity = activity!!
                if (!activity.app_bar.isExpanded) {
//                  recents_list.smoothScrollToPosition(position) //wont work even witrh smoothscroller

                    if (smoothScroller == null)
                        smoothScroller = object : LinearSmoothScroller(recents_list.context) {
                            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics?) =
                                    super.calculateSpeedPerPixel(displayMetrics) * 3

                            override fun getVerticalSnapPreference() = SNAP_TO_START
                        }
                    smoothScroller?.targetPosition = position
                    recents_list.layoutManager?.startSmoothScroll(smoothScroller)
                    activity.app_bar?.setExpanded(true, true)
                }

                if (!view.isInTouchMode)
                    openTrackPopupMenu(view.date_frame, item)
            }
        }
    }

    //only called when !view.isInTouchMode
    override fun onFocus(view: View, position: Int) {
        if (!view.isInTouchMode) {
            val pos = IntArray(2)
            view.getLocationInWindow(pos)

            if (pos[1] + view.height > activity!!.coordinator.height && activity!!.app_bar.isExpanded)
                activity!!.app_bar.setExpanded(false, true)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun openTrackPopupMenu (anchor: View, track: Track) {
        val menuBuilder = MenuBuilder(context)
        val inflater = SupportMenuInflater(context)
        if (username == null) {
            inflater.inflate(R.menu.recents_item_menu, menuBuilder)
            val loveMenu = menuBuilder.findItem(R.id.menu_love)

            if (track.isLoved) {
                loveMenu.title = getString(R.string.unlove)
                loveMenu.icon = ContextCompat.getDrawable(context!!, R.drawable.vd_heart_break_outline)
            }
            if (track.playedWhen == null)
                menuBuilder.removeItem(R.id.menu_delete)

            if (isShowingLoves) {
                menuBuilder.removeItem(R.id.menu_delete)
                menuBuilder.removeItem(R.id.menu_edit)
            }
        }
        if (!anchor.isInTouchMode)
            inflater.inflate(R.menu.recents_item_tv_menu, menuBuilder)
        fun csrfTokenExists(): Boolean {
            val prefs = MultiPreferences(context!!)
            val exists = LastfmUnscrobbler(context)
                    .checkCsrf(prefs.getString(Stuff.PREF_LASTFM_USERNAME, null))
            if (!exists) {
                AlertDialog.Builder(context!!, R.style.DarkDialog)
                        .setMessage(R.string.lastfm_reauth)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val wf = WebViewFragment()
                            val b = Bundle()
                            b.putString(Stuff.ARG_URL, Stuff.LASTFM_AUTH_CB_URL)
                            b.putBoolean(Stuff.ARG_SAVE_COOKIES, true)
                            wf.arguments = b
                            activity!!.supportFragmentManager.beginTransaction()
                                    .replace(R.id.frame, wf)
                                    .addToBackStack(null)
                                    .commit()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
            }
            return exists
        }
        menuBuilder.setCallback(object : MenuBuilder.Callback {
            override fun onMenuItemSelected(menu: MenuBuilder, item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.menu_love -> {
                        loveToggle((anchor.parent as ViewGroup).recents_img_overlay, track)
                    }
                    R.id.menu_edit -> {
                        if (!Main.isOnline)
                            Stuff.toast(context, getString(R.string.unavailable_offline))
                        else if (csrfTokenExists()) {
                            val b = Bundle()
                            b.putString(NLService.B_ARTIST, track.artist)
                            b.putString(NLService.B_ALBUM, track.album)
                            b.putString(NLService.B_TITLE, track.name)

                            val millis = track.playedWhen?.time
                            if (millis != null)
                                b.putLong(NLService.B_TIME, millis)

                            val ef = EditFragment()
                            ef.arguments = b
                            ef.show(activity!!.supportFragmentManager, null)
                        }
                    }
                    R.id.menu_delete -> {
                        if (!Main.isOnline)
                            Stuff.toast(context, getString(R.string.unavailable_offline))
                        else if (csrfTokenExists()) {
                            LFMRequester(context!!).delete(track) { succ ->
                                        if (succ) {
                                            view?.post {
                                                adapter.removeTrack(track)
                                            }
                                        } else
                                            Stuff.toast(context, getString(R.string.network_error))
                                    }
                                    .asAsyncTask()
                        }
                    }
                    R.id.menu_play -> activity!!.hero_play.callOnClick()
                    R.id.menu_info -> activity!!.hero_info.callOnClick()
                    R.id.menu_share -> activity!!.hero_share.callOnClick()
                    R.id.menu_calendar -> activity!!.hero_calendar.callOnClick()
                    else -> return false
                }
                return true
            }

            override fun onMenuModeChange(menu: MenuBuilder) {}
        })

        val popupMenu = MenuPopupHelper(context!!, menuBuilder, anchor)
        popupMenu.setForceShowIcon(true)
        popupMenu.show()
    }

    private fun loveToggle(loveIcon:View, track: Track) {
        val alphaAnimator = ObjectAnimator.ofFloat(loveIcon, "alpha", 0f)
        val scalexAnimator = ObjectAnimator.ofFloat(loveIcon, "scaleX", 0f)
        val scaleyAnimator = ObjectAnimator.ofFloat(loveIcon, "scaleY", 0f)
        val rotAnimator = ObjectAnimator.ofFloat(loveIcon, "rotation", 0f)

        val isRtl = resources.getBoolean(R.bool.is_rtl)
        if (track.isLoved) {
            LFMRequester(context!!).loveOrUnlove(false, track.artist, track.name).asAsyncTask()

            alphaAnimator.setFloatValues(0f)
            scalexAnimator.setFloatValues(1f, 0.5f)
            scaleyAnimator.setFloatValues(1f, 0.5f)
            val startRot = if (isRtl) -10f else 10f
            val toRot = if (isRtl) -60f else 60f
            rotAnimator.setFloatValues(startRot, toRot)
        } else {
            if (loveIcon.background == null)
                loveIcon.background = ContextCompat.getDrawable(context!!, R.drawable.vd_heart_stroked)

            LFMRequester(context!!).loveOrUnlove(true, track.artist, track.name).asAsyncTask()

            loveIcon.alpha = 0f
            loveIcon.visibility = View.VISIBLE
            alphaAnimator.setFloatValues(0.9f)
            scalexAnimator.setFloatValues(2f, 1f)
            scaleyAnimator.setFloatValues(2f, 1f)
            val startRot = if (isRtl) 50f else -50f
            val toRot = if (isRtl) -10f else 10f
            rotAnimator.setFloatValues(startRot, toRot)
        }
        val aSet = AnimatorSet()
        aSet.playTogether(alphaAnimator, scalexAnimator, scaleyAnimator, rotAnimator)
        aSet.interpolator = OvershootInterpolator()
        aSet.duration = 800
        aSet.start()

        track.isLoved = !track.isLoved
    }

    companion object {
        private var lastColorLightWhite = Color.WHITE
        private var lastColorMutedDark = Color.BLACK
        var lastColorMutedBlack = Color.BLACK
    }
}
