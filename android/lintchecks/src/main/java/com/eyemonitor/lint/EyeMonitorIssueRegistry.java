package com.eyemonitor.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.detector.api.Issue;

import java.util.Arrays;
import java.util.List;

/** 眼互自定义 lint 注册表（AndroidManifest 的 Lint-Registry 指向此类） */
public class EyeMonitorIssueRegistry extends IssueRegistry {

    @Override
    public List<Issue> getIssues() {
        return Arrays.asList(NakedButtonDetector.ISSUE);
    }
}