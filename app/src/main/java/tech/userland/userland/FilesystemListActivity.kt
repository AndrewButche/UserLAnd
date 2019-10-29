package tech.userland.userland

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import kotlinx.android.synthetic.main.activity_filesystem_list.*
import tech.userland.userland.database.models.Filesystem
import tech.userland.userland.ui.FilesystemViewModel


class FilesystemListActivity: AppCompatActivity() {

    lateinit var filesystemList: List<Filesystem>

//    private val filesystemViewModelFactory: FilesystemViewModelFactory by lazy {
//        provideFilesystemViewModelFactory(this)
//    }

    private val fileSystemViewModel: FilesystemViewModel by lazy {
//        ViewModelProviders.of(this, filesystemViewModelFactory).get(FilesystemViewModel::class.java)
        ViewModelProviders.of(this).get(FilesystemViewModel::class.java)
    }

    private val filesystemChangeObserver = Observer<List<Filesystem>> {
        it?.let {
            filesystemList = it
            val filesystemNames = filesystemList.map { filesystem -> filesystem.name }

            list_file_system_management.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filesystemNames)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filesystem_list)
        setSupportActionBar(toolbar)

        fileSystemViewModel.getAllFilesystems().observe(this, filesystemChangeObserver)

        registerForContextMenu(list_file_system_management)

        fab.setOnClickListener { navigateToFilesystemEdit("") }
    }

    override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        menuInflater.inflate(R.menu.context_menu_file_systems, menu)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val menuInfo = item.menuInfo as AdapterView.AdapterContextMenuInfo
        val position = menuInfo.position
        val filesystem = filesystemList[position]

        return when (item.itemId) {
            R.id.menu_item_file_system_edit -> navigateToFilesystemEdit(filesystem.name)
//            R.id.menu_item_file_system_delete -> deleteFilesystem(filesystem)
            else -> super.onContextItemSelected(item)
        }
    }

    fun navigateToFilesystemEdit(filesystemName: String): Boolean {
        val intent = Intent(this, FilesystemEditActivity::class.java)
        intent.putExtra("filesystemName", filesystemName)
        startActivity(intent)
        return true
    }

//    fun deleteFilesystem(filesystem: Filesystem): Boolean {
//        FilesystemRepository(this).deleteFilesystem(filesystem)
//        return true
//    }
}