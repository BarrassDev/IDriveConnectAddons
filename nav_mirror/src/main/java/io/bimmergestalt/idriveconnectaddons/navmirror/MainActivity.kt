package io.bimmergestalt.idriveconnectaddons.navmirror

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import io.bimmergestalt.idriveconnectaddons.navmirror.databinding.ActivityMainBinding

class MainActivity: AppCompatActivity() {
    val viewModel by viewModels<MainModel>()
    val controller by lazy { MainController(this, viewModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavMirror.init(applicationContext)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        binding.lifecycleOwner = this
        binding.controller = controller
        binding.viewModel = viewModel
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        viewModel.update(this)
    }
}
