package iss.nus.edu.sg.viewbinding.caproject.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import iss.nus.edu.sg.viewbinding.caproject.R
import iss.nus.edu.sg.viewbinding.caproject.databinding.ActivityMainBinding
import iss.nus.edu.sg.viewbinding.caproject.ui.claims.ClaimsFragment
import iss.nus.edu.sg.viewbinding.caproject.ui.expense.ExpensesFragment
import iss.nus.edu.sg.viewbinding.caproject.ui.home.HomeFragment
import iss.nus.edu.sg.viewbinding.caproject.ui.trips.TripsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showScreen(item.itemId)
        }

        if (savedInstanceState == null) selectRequestedTab(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectRequestedTab(intent)
    }

    fun selectTab(itemId: Int) {
        binding.bottomNavigation.selectedItemId = itemId
    }

    private fun selectRequestedTab(intent: Intent) {
        val requestedTab = intent.getIntExtra(EXTRA_SELECTED_TAB, R.id.navigation_home)
        binding.bottomNavigation.selectedItemId = requestedTab
    }

    private fun showScreen(itemId: Int): Boolean {
        val tag = itemId.toString()
        val currentScreen = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
        if (currentScreen?.tag == tag) return true

        val screen: Fragment = when (itemId) {
            R.id.navigation_home -> HomeFragment()
            R.id.navigation_trips -> TripsFragment()
            R.id.navigation_expenses -> ExpensesFragment()
            R.id.navigation_claims -> ClaimsFragment()
            else -> return false
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.mainFragmentContainer, screen, tag)
            .commit()
        return true
    }

    companion object {
        const val EXTRA_SELECTED_TAB = "selected_tab"
    }
}
