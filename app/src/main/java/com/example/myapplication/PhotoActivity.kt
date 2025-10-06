package com.example.myapplication

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.example.myapplication.databinding.ActivityPhotoBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PhotoActivity : AppCompatActivity() {
    private lateinit var b: ActivityPhotoBinding
    private var photoUri: Uri? = null
    private lateinit var outFile: File

    private val pickGallery = registerForActivityResult(ActivityResultContracts.GetContent()){
        it?.let(::showImage)
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()){ ok ->
        if (ok) { saveToGallery(outFile); showImage(Uri.fromFile(outFile)) }
    }
    private val reqCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()){
        if (it) openCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnGallery.setOnClickListener { pickGallery.launch("image/*") }
        b.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) openCamera()
            else reqCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        outFile = File.createTempFile("IMG_${ts()}", ".jpg", cacheDir)
        photoUri = FileProvider.getUriForFile(this, "$packageName.provider", outFile)
        takePicture.launch(photoUri)
    }

    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${ts()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun showImage(uri: Uri) {
        Glide.with(this).load(uri).into(b.imageView)
        contentResolver.openInputStream(uri)?.use {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(it, null, o)
        }
    }

    private fun ts() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
