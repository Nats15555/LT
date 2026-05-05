package com.loadtest.summarization.service;

import com.loadtest.summarization.persistence.SummarizerConfig;

public interface SummarizerClient {

    String summarize(SummarizerConfig config, String prompt);
}
