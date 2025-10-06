
package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ActivityContactsBinding
import com.example.myapplication.databinding.ItemContactBinding

class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private val adapter = ContactsAdapter()

    private val reqContacts =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadContacts() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = adapter

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) loadContacts()
        else reqContacts.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun loadContacts() {
        val list = mutableListOf<String>()
        val c = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        )
        c?.use { while (it.moveToNext()) list.add(it.getString(0) ?: "") }
        adapter.submit(list)
    }

    class ContactsAdapter : RecyclerView.Adapter<ContactsVH>() {
        private val data = mutableListOf<String>()
        fun submit(newData: List<String>) { data.clear(); data.addAll(newData); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactsVH {
            val b = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ContactsVH(b)
        }
        override fun onBindViewHolder(holder: ContactsVH, position: Int) = holder.bind(data[position])
        override fun getItemCount() = data.size
    }

    class ContactsVH(private val b: ItemContactBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(name: String) { b.tvName.text = name }
    }
}

