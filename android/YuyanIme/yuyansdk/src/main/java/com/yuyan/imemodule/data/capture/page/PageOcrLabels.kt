package com.yuyan.imemodule.data.capture.page

/** 同一帧原生OCR元素保留其真实框；完整行仍用于敏感提示和金额，不猜分词或移动框。 */
internal fun pageOcrLineLabels(line: PageLabel?, elements: List<PageLabel>): List<PageLabel> =
    (listOfNotNull(line) + elements).distinct()
