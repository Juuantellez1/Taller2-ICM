package com.example.myapplication

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.myapplication.databinding.ActivityPhotoBinding
import java.text.SimpleDateFormat
import java.util.*

class PhotoActivity : AppCompatActivity() {

    private lateinit var b: ActivityPhotoBinding
    private var photoUri: Uri? = null

    // Galería (leer imagen existente)
    private val pickGallery =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { showImage(it) }
        }

    // Cámara: toma foto *en el Uri de MediaStore*
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) photoUri?.let { showImage(it) }
        }

    private val reqCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnGallery.setOnClickListener { pickGallery.launch("image/*") }

        b.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                openCamera()
            } else {
                reqCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /** Crea un Uri en MediaStore y lanza la cámara para escribir ahí (máxima calidad). */
    private fun openCamera() {
        photoUri = createImageUri() ?: run {
            // Si no se pudo crear el Uri, no lances la cámara
            return
        }
        takePicture.launch(photoUri)
    }

    /** Crea la entrada en la galería (MediaStore) y devuelve su Uri. */
    private fun createImageUri(): Uri? {
        val name = "IMG_${ts()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // Opcional: guarda en DCIM/Camera en Android 10+
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }
        return contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
    }

    private fun showImage(uri: Uri) {
        Glide.with(this).load(uri).into(b.imageView)
    }

    private fun ts() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}