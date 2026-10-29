package com.johncolemantw.exceptionseam.scanner

import com.johncolemantw.exceptionseam.model.Seam
import java.io.File

interface SeamScanner {
    fun scan(sourceRoots: Collection<File>): List<Seam>
}
