package br.tvreporter.srt

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.backCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.frontCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isBackCamera
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.interfaces.setCameraId

@RequiresPermission(Manifest.permission.CAMERA)
suspend fun IWithVideoSource.switchBackToFront(context: Context) {
    val manager = context.cameraManager
    val currentSource = videoInput.sourceFlow.value
    val targetCameras = if (currentSource is ICameraSource) {
        if (manager.isBackCamera(currentSource.cameraId)) manager.frontCameras else manager.backCameras
    } else {
        manager.backCameras
    }

    if (targetCameras.isEmpty()) error("Nenhuma câmera compatível encontrada")
    setCameraId(targetCameras.first())
}
