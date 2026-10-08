package dev.handeye.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 单页信息流：列表 + 下拉刷新 + 点击点赞。handeye 的断点不在这，在因果链。 */
class MainActivity : Activity() {
    private lateinit var viewModel: FeedViewModel
    private lateinit var adapter: FeedAdapter
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleBootstrapIntent(intent)
        viewModel = (application as FeedApp).viewModel

        val refresh = SwipeRefreshLayout(this)
        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        adapter = FeedAdapter { id -> viewModel.onToggleLike(id) }
        list.adapter = adapter
        refresh.addView(list)
        refresh.setOnRefreshListener { viewModel.onRefresh() }
        setContentView(refresh)

        uiScope.launch {
            viewModel.uiState.collect { state ->
                refresh.isRefreshing = state.isLoading
                adapter.submit(state.items)
            }
        }

        if (savedInstanceState == null) viewModel.onRefresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleBootstrapIntent(intent)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /** bootstrap deeplink 落值：冷启动走 onCreate，热启动走 onNewIntent。 */
    private fun handleBootstrapIntent(intent: Intent?) {
        BootstrapIntentParser.parse(intent)?.let { BootstrapState.args.value = it }
    }
}

private class FeedAdapter(
    private val onLike: (Int) -> Unit,
) : RecyclerView.Adapter<FeedAdapter.Holder>() {
    private var items: List<FeedItem> = emptyList()

    fun submit(newItems: List<FeedItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.text.text = (if (item.liked) "♥ " else "") + item.title
        holder.itemView.setOnClickListener { onLike(item.id) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(android.R.id.text1)
    }
}
