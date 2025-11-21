package com.ai.growsight

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object TFLiteHelper {

    fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    fun getInterpreter(context: Context, fileName: String): Interpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        return Interpreter(loadModelFile(context, fileName), options)
    }
}
