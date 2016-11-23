package query_translation;

import clauseObjects.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

class MultipleRel {
    // alphabet allows for a consistent and logical naming approach to the intermediate parts
    // of the queries. Assumption is that # of relationships in a query has an upper bound of 26.
    private static final char[] alphabet = "abcdefghijklmnopqrstuvwxyz".toCharArray();

    static StringBuilder translate(StringBuilder sql, DecodedQuery decodedQuery) throws Exception {
        sql = obtainWithClause(sql, decodedQuery.getMc());
        sql = obtainSelectAndFromClause(decodedQuery.getRc(), decodedQuery.getMc(), sql,
                decodedQuery.getCypherAdditionalInfo().hasDistinct(),
                decodedQuery.getCypherAdditionalInfo().getAliasMap());
        sql = obtainWhereClause(sql, decodedQuery.getRc(), decodedQuery.getMc());
        return sql;
    }

    /**
     * Obtain WITH clause (Common Table Expression) for query with relationships.
     *
     * @param sql    Existing SQL
     * @param matchC Match Clause of the original Cypher query.
     * @return New SQL.
     */
    private static StringBuilder obtainWithClause(StringBuilder sql, MatchClause matchC) {
        sql.append("WITH ");
        int indexRel = 0;

        for (CypRel cR : matchC.getRels()) {
            String withAlias = String.valueOf(alphabet[indexRel]);
            sql.append(withAlias).append(" AS ");
            sql.append("(SELECT n1.id AS ").append(withAlias).append(1).append(", ");
            sql.append("n2.id AS ").append(withAlias).append(2);
            sql.append(", e").append(indexRel + 1).append(".*");

            switch (cR.getDirection()) {
                case "right":
                    sql.append(" FROM nodes n1 " + "INNER JOIN edges e").append(indexRel + 1)
                            .append(" on n1.id = e").append(indexRel + 1).append(".idl ")
                            .append("INNER JOIN nodes n2 on e").append(indexRel + 1).append(".idr = n2.id");
                    sql = obtainWhereInWithClause(cR, matchC, sql, false, indexRel);
                    break;
                case "left":
                    sql.append(" FROM nodes n1 " + "INNER JOIN edges e").append(indexRel + 1)
                            .append(" on n1.id = e").append(indexRel + 1).append(".idr ")
                            .append("INNER JOIN nodes n2 on e").append(indexRel + 1).append(".idl = n2.id");
                    sql = obtainWhereInWithClause(cR, matchC, sql, false, indexRel);
                    break;
                case "none":
                    sql.append(" FROM nodes n1 " + "INNER JOIN edges e").append(indexRel + 1)
                            .append(" on n1.id = e").append(indexRel + 1).append(".idl ")
                            .append("INNER JOIN nodes n2 on e").append(indexRel + 1).append(".idr = n2.id");
                    sql = obtainWhereInWithClause(cR, matchC, sql, true, indexRel);
                    sql.append("SELECT n1.id AS ").append(withAlias).append(1).append(", ");
                    sql.append("n2.id AS ").append(withAlias).append(2);
                    sql.append(", e").append(indexRel + 1).append(".*");
                    sql.append(" FROM nodes n1 " + "INNER JOIN edges e").append(indexRel + 1)
                            .append(" on n1.id = e").append(indexRel + 1).append(".idr ")
                            .append("INNER JOIN nodes n2 on e").append(indexRel + 1).append(".idl = n2.id");
                    sql = obtainWhereInWithClause(cR, matchC, sql, false, indexRel);
                    break;
            }

            indexRel++;
        }

        sql.setLength(sql.length() - 2);
        sql.append(" ");
        return sql;
    }

    /**
     * Obtains the WHERE within each WITH CTE.
     *
     * @param cR              Relationship to map.
     * @param matchC          Match Clause of Cypher.
     * @param sql             Existing SQL.
     * @param isBiDirectional Does the Cypher relationship map both directions (i.e. -[]-)
     * @param indexRel        Where is the relationship in the whole context of the match clause (index starts at 1)
     * @return New SQL.
     */
    private static StringBuilder obtainWhereInWithClause(CypRel cR, MatchClause matchC, StringBuilder sql,
                                                         boolean isBiDirectional, int indexRel) {
        boolean includesWhere = false;
        int posOfRel = cR.getPosInClause();

        CypNode leftNode = obtainNode(matchC, posOfRel);
        JsonObject leftProps = leftNode.getProps();
        CypNode rightNode = obtainNode(matchC, posOfRel + 1);
        JsonObject rightProps = rightNode.getProps();
        String typeRel = cR.getType();
        JsonObject o = cR.getProps();

        if (leftProps != null) {
            sql.append(" WHERE ");
            includesWhere = true;

            Set<Map.Entry<String, JsonElement>> entrySet = leftProps.entrySet();
            for (Map.Entry<String, JsonElement> entry : entrySet) {
                sql.append("n1").append(".").append(entry.getKey());
                sql = TranslateUtils.addWhereClause(sql, entry);
            }
        }

        if (rightProps != null) {
            if (!includesWhere) {
                sql.append(" WHERE ");
                includesWhere = true;
            }

            Set<Map.Entry<String, JsonElement>> entrySet = rightProps.entrySet();
            for (Map.Entry<String, JsonElement> entry : entrySet) {
                sql.append("n2").append(".").append(entry.getKey());
                sql = TranslateUtils.addWhereClause(sql, entry);
            }
        }

        if (leftNode.getType() != null) {
            if (!includesWhere) {
                sql.append(" WHERE ");
                includesWhere = true;
            }
            sql.append("n1.label LIKE ");
            sql.append(TranslateUtils.genLabelLike(leftNode)).append(" AND ");
        }

        if (rightNode.getType() != null) {
            if (!includesWhere) {
                sql.append(" WHERE ");
                includesWhere = true;
            }
            sql.append("n2.label LIKE ");
            sql.append(TranslateUtils.genLabelLike(rightNode)).append(" AND ");
        }

        if (typeRel != null) {
            if (!includesWhere) {
                sql.append(" WHERE ");
                includesWhere = true;
            }

            sql.append("e").append(indexRel + 1).append(".type = '").append(typeRel);
            sql.append("' AND ");
        }

        if (o != null) {
            if (!includesWhere) {
                sql.append(" WHERE ");
                includesWhere = true;
            }

            Set<Map.Entry<String, JsonElement>> entries = o.entrySet();
            for (Map.Entry<String, JsonElement> entry : entries) {
                sql.append("e").append(indexRel + 1).append(".").append(entry.getKey()).append(" = '");
                sql.append(entry.getValue().getAsString()).append("' AND ");
            }
        }

        if (includesWhere) sql.setLength(sql.length() - 5);
        if (isBiDirectional) {
            sql.append(" UNION ALL ");
        } else {
            sql.append("), ");
        }
        return sql;
    }

    /**
     * @param matchC
     * @param i
     * @return
     */
    private static CypNode obtainNode(MatchClause matchC, int i) {
        for (CypNode c : matchC.getNodes()) {
            if (c.getPosInClause() == i) {
                return c;
            }
        }
        return null;
    }

    /**
     * @param returnC
     * @param matchC
     * @param sql
     * @param hasDistinct
     * @param alias
     * @return
     * @throws Exception
     */
    private static StringBuilder obtainSelectAndFromClause(ReturnClause returnC, MatchClause matchC,
                                                           StringBuilder sql, boolean hasDistinct,
                                                           Map<String, String> alias)
            throws Exception {
        sql.append("SELECT ");
        if (hasDistinct) sql.append("DISTINCT ");

        for (CypReturn cR : returnC.getItems()) {
            boolean isNode = false;

            if (cR.getNodeID() == null && cR.getField().equals("*")) {
                sql.append("*  ");
                break;
            }

            if (cR.getField() != null && cR.getField().startsWith("count")) {
                String toAdd;
                int posInCluase = cR.getPosInClause();
                if (posInCluase == 1) toAdd = "a1";
                else toAdd = "a2";
                sql.append("count(").append(toAdd).append(")");
                sql.append(useAlias(cR.getField(), alias)).append(", ");
                break;
            }

            for (CypNode cN : matchC.getNodes()) {
                if (cR.getNodeID().equals(cN.getId())) {
                    String prop = cR.getField();
                    if (prop != null) {
                        sql.append("n").append(".").append(prop).append(useAlias(cR.getNodeID(), alias)).append(", ");
                    } else {
                        sql.append("n.*").append(useAlias(cR.getNodeID(), alias)).append(", ");
                    }
                    isNode = true;
                    break;
                }
            }

            if (!isNode) {
                for (CypRel cRel : matchC.getRels()) {
                    if (cRel.getId() != null && cRel.getId().equals(cR.getNodeID())) {
                        String prop = cR.getField();
                        int relPos = cRel.getPosInClause();
                        String idRel = (relPos == 1) ? "a" : (relPos == 2) ? "b" : (relPos == 3) ? "c" : "a";
                        if (prop != null) {
                            sql.append(idRel).append(".").append(prop)
                                    .append(useAlias(cR.getNodeID(), alias)).append(", ");
                        } else {
                            sql.append(idRel).append(".*")
                                    .append(useAlias(cR.getNodeID(), alias)).append(", ");
                        }
                        break;
                    }
                }
            }
        }

        sql.setLength(sql.length() - 2);

        sql.append(" FROM nodes n, ");

        int numRels = matchC.getRels().size();
        for (int i = 0; i < numRels; i++)
            sql.append(alphabet[i]).append(", ");

        sql.setLength(sql.length() - 2);
        sql.append(" ");
        return sql;
    }

    /**
     * @param nodeID
     * @param alias
     * @return
     */
    private static String useAlias(String nodeID, Map<String, String> alias) {
        if (alias.isEmpty()) {
            return "";
        } else {
            for (String s : alias.keySet()) {
                String id = s.split("\\.")[0];
                if (id.equals(nodeID)) {
                    return (" AS " + alias.get(s));
                }
            }
        }
        return "";
    }

    /**
     * @param sql
     * @param returnC
     * @param matchC
     * @return
     * @throws Exception
     */
    private static StringBuilder obtainWhereClause(StringBuilder sql,
                                                   ReturnClause returnC, MatchClause matchC) throws Exception {
        sql.append(" WHERE ");
        int numRels = matchC.getRels().size();

        for (int i = 0; i < numRels - 1; i++) {
            sql.append(alphabet[i]).append(".").append(alphabet[i]).append(2);
            sql.append(" = ");
            sql.append(alphabet[i + 1]).append(".").append(alphabet[i + 1]).append(1);
            sql.append(" AND ");
        }

        if ((numRels == 1)) {
            if (returnC.getItems().size() == 1 && returnC.getItems().get(0).getType().equals("rel")) {
                if (matchC.getRels().get(0).getDirection().equals("left")) {
                    sql.append(" a.a1 = n.idr AND a.a2 = n.idl ");
                } else if (matchC.getRels().get(0).getDirection().equals("right")) {
                    sql.append(" a.a1 = n.idl AND a.a2 = n.idr ");
                } else
                    sql.append(" (a.a1 = n.idl AND a.a2 = n.idr) OR (a.a1 = n.idr AND a.a2 = n.idl) ");
            } else if (matchC.getRels().get(0).getDirection().equals("none")) {
                int posInCl = returnC.getItems().get(0).getPosInClause();
                if (posInCl == 1) return sql.append(" n.id = a.a1");
                else return sql.append("n.id = a.a2");
            }
        }

        ArrayList<String> nodeIDS = new ArrayList<>();
        ArrayList<String> crossResults = new ArrayList<>();

        for (CypNode cN : matchC.getNodes()) {
            if (cN.getId() != null) {
                if (nodeIDS.contains(cN.getId())) {
                    crossResults.add(nodeIDS.indexOf(cN.getId()) + "," +
                            (nodeIDS.size() + crossResults.size()));
                } else nodeIDS.add(cN.getId());
            }
        }

        for (CypReturn cR : returnC.getItems()) {
            if (cR.getType() != null) {
                switch (cR.getType()) {
                    case "node":
                        int posInClause = cR.getPosInClause();
                        sql.append("n.id = ");

                        if (posInClause == 1) {
                            sql.append("a.a1");
                            sql.append(" AND ");
                        } else {
                            sql.append(alphabet[posInClause - 2]).append(".").append(alphabet[posInClause - 2])
                                    .append(2);
                            sql.append(" AND ");
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        if (numRels > 1) {
            for (int i = 0; i < numRels - 1; i++) {
                if (i == 0) {
                    sql.append("a.a1 != b.b2");
                } else {
                    sql.append(alphabet[i - 1]).append(".").append(alphabet[i - 1]).append(2);
                    sql.append(" != ");
                    sql.append(alphabet[i + 1]).append(".").append(alphabet[i + 1]).append(2);
                }
                sql.append(" AND ");
            }
        }

        if (!crossResults.isEmpty()) {
            for (String s : crossResults) {
                String[] t = s.split(",");
                int[] indices = new int[2];
                indices[0] = Integer.parseInt(t[0]);
                indices[1] = Integer.parseInt(t[1]);
                String a;
                String b;
                if (indices[0] == 0) a = "a.a1";
                else a = alphabet[indices[0] - 1] + "." + alphabet[indices[0] - 1] + "2";
                b = alphabet[indices[1] - 1] + "." + alphabet[indices[1] - 1] + "2";
                sql.append(a).append(" = ").append(b).append(" AND ");
            }
        }

        if (sql.toString().endsWith(" AND ")) sql.setLength(sql.length() - 5);

        return sql;
    }

}