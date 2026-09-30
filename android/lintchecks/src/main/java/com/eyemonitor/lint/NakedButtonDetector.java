package com.eyemonitor.lint;

import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.XmlContext;
import com.android.tools.lint.detector.api.XmlScanner;

import org.w3c.dom.Element;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * 眼互 UI 规范 lint 规则：
 * 布局中的裸 <Button>（无 style 且无自绘 background）→ ERROR
 */
public class NakedButtonDetector extends Detector implements XmlScanner {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    public static final Issue ISSUE = Issue.create(
            "NakedButton",
            "裸 Button 缺少样式",
            "布局中的 <Button> 必须带 style=（推荐 Widget.EyeMonitor.Button.*）或自绘 android:background，"
                    + "统一走设计体系，避免 Material 默认形态混入。",
            Category.CORRECTNESS, 7, Severity.ERROR,
            new Implementation(NakedButtonDetector.class, Scope.RESOURCE_FILE_SCOPE));

    @Override
    public Collection<String> getApplicableElements() {
        return Collections.singletonList("Button");
    }

    @Override
    public void visitElement(XmlContext context, Element element) {
        File f = context.file;
        if (f == null || f.getParentFile() == null
                || !"layout".equals(f.getParentFile().getName())) {
            return;
        }
        boolean hasStyle = element.hasAttribute("style");
        boolean hasCustomBackground = element.hasAttributeNS(ANDROID_NS, "background");
        if (!hasStyle && !hasCustomBackground) {
            context.report(ISSUE, element, context.getLocation(element),
                    "`<Button>` 缺少 style（应使用 Widget.EyeMonitor.Button.*）且无自绘 background");
        }
    }
}