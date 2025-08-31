//package com.itplace.userapi.ai.question.service;
//
//import java.util.List;
//
//public class PgVectorUtil {
//    private PgVectorUtil() {
//    }
//
//    public static String toVectorLiteral(List<Float> vec) {
//        StringBuilder sb = new StringBuilder(vec.size() * 8).append('[');
//        for (int i = 0; i < vec.size(); i++) {
//            if (i > 0) {
//                sb.append(',');
//            }
//            float v = vec.get(i);
//            if (!Float.isFinite(v)) {
//                throw new IllegalArgumentException("Non-finite value in embedding");
//            }
//            sb.append(v);
//        }
//        return sb.append(']').toString();
//    }
//}
//