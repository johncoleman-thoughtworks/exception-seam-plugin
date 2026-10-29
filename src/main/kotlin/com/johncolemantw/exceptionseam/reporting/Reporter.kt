package com.johncolemantw.exceptionseam.reporting

import com.johncolemantw.exceptionseam.model.AnalysisResult

interface Reporter {
    fun report(result: AnalysisResult)
}
