package com.blurr.voice

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

class ProPurchaseActivity : BaseNavigationActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pro_purchase)

        findViewById<View>(R.id.back_button).setOnClickListener {
            finish()
        }
        findViewById<TextView>(R.id.price_text).text = "Unlocked"
        findViewById<ProgressBar>(R.id.loading_progress).visibility = View.GONE
        findViewById<Button>(R.id.purchase_button).apply {
            text = "All Miko features are unlocked"
            isEnabled = false
            visibility = View.VISIBLE
        }
    }

    override fun getContentLayoutId(): Int {
        return R.layout.activity_pro_purchase
    }

    override fun getCurrentNavItem(): NavItem {
        return BaseNavigationActivity.NavItem.HOME
    }
}
