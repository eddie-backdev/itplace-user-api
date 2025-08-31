//package com.itplace.userapi.ai.question.service;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.beans.factory.annotation.Qualifier;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Service;
//
/// **
// * QuestionIndexer에서 사용하던 ES 구현을 PG + pgvector로 교체한 버전 - indexName 파라미터는 "테이블명"으로 사용됩니다. - embedding 차원은 1536으로 고정(필요 시
// * DIMS 수정)
// */
//
//@Service
//@RequiredArgsConstructor
//public class PgVectorQuestionServiceImpl implements PgVectorQuestionService {
//
//    @Qualifier("pgVectorJdbc")  // pgvector용 JdbcTemplate (별도 DS를 붙였다고 가정)
//    private final JdbcTemplate jdbc;
//
//    private static final int DIMS = 1536;
//
//    /**
//     * 테이블명 안전 체크(영문/숫자/언더스코어만 허용)
//     */
//    private static String safeIdent(String name) {
//        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
//            throw new IllegalArgumentException("Illegal table name: " + name);
//        }
//        return name;
//    }
//
//    /**
//     * question_embeddings 인덱스가 없으면 생성 (PG에선 테이블+인덱스) - 컬럼: id TEXT PK, question TEXT, category TEXT, embedding
//     * VECTOR(1536) - HNSW 인덱스 + 카테고리 인덱스
//     */
//    @Override
//    public void createQuestionIndexIfNotExists(String indexName) {
//        String table = safeIdent(indexName);
//
//        // 확장 설치
//        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
//
//        // 테이블 생성
//        jdbc.execute(String.format("""
//                CREATE TABLE IF NOT EXISTS %s (
//                  id         TEXT PRIMARY KEY,
//                  question   TEXT NOT NULL,
//                  category   TEXT,
//                  embedding  VECTOR(%d) NOT NULL,
//                  created_at TIMESTAMPTZ DEFAULT now(),
//                  updated_at TIMESTAMPTZ DEFAULT now()
//                )
//                """, table, DIMS));
//
//        // HNSW 인덱스 (코사인 거리)
//        jdbc.execute(String.format("""
//                CREATE INDEX IF NOT EXISTS idx_%s_embedding_hnsw
//                  ON %s USING hnsw (embedding vector_cosine_ops)
//                  WITH (m = 16, ef_construction = 64)
//                """, table, table));
//
//        // 카테고리 필터용 보조 인덱스
//        jdbc.execute(String.format("""
//                CREATE INDEX IF NOT EXISTS idx_%s_category ON %s(category)
//                """, table, table));
//
//        System.out.println("Created/validated table: " + table);
//    }
//
//    /**
//     * 주어진 ID가 해당 테이블에 존재하는지 확인
//     */
//    @Override
//    public boolean existsById(String indexName, String id) {
//        String table = safeIdent(indexName);
//        Boolean exists = jdbc.queryForObject(
//                "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id = ?)",
//                Boolean.class, id
//        );
//        return Boolean.TRUE.equals(exists);
//    }
//
//    /**
//     * 질문 문서 단일 저장 (UPSERT) - ES의 index() 호출에 대응: 같은 id면 업데이트
//     */
//    @Override
//    public void saveQuestion(String indexName, String id, String question, String category, float[] embedding) {
//        String table = safeIdent(indexName);
//
//        // List<Float> -> '[...]' 문자열 리터럴로 직렬화 후 ::vector 캐스팅
//        String vec = PgVectorUtil.toVectorLiteral(embedding);
//
//        jdbc.update(
//                ("INSERT INTO %s (id, question, category, embedding, updated_at) " +
//                        "VALUES (?, ?, ?, ?::vector, now()) " +
//                        "ON CONFLICT (id) DO UPDATE SET " +
//                        "  question = EXCLUDED.question, " +
//                        "  category = EXCLUDED.category, " +
//                        "  embedding = EXCLUDED.embedding, " +
//                        "  updated_at = now()").formatted(table),
//                id, question, category, vec
//        );
//    }
//}
