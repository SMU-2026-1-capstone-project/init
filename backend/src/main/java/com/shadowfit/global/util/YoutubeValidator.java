package com.shadowfit.global.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeValidator {
    private static final Pattern YOUTUBE_PATTERN =
            Pattern.compile("(?<=watch\\?v=|/videos/|embed/|youtu.be/|shorts/)[^#&?\\s]{11}");

    public static String extractId(String url){
        Matcher matcher = YOUTUBE_PATTERN.matcher(url);

        if(matcher.find()){
            return matcher.group();
        }
        throw new IllegalArgumentException("유효하지 않은 유튜브 주소입니다.");
    }
}
