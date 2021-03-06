/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.parse.sql.transformer.fb.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.antlr33.runtime.tree.CommonTree;
import org.apache.hadoop.hive.ql.parse.sql.PantheraExpParser;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateException;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateUtil;
import org.apache.hadoop.hive.ql.parse.sql.TranslateContext;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.FilterBlockUtil;

import br.com.porcelli.parser.plsql.PantheraParser_PLSQLParser;

/**
 * provide common process logic for filter block processor. TODO<br>
 * <li>processCompareHavingUC and processEqualsUC didn¡¯t check whether single-row expression
 * returns only one row. It¡¯s ok for these 2 cases because sum and max always return one value.
 * Just a reminder don¡¯t miss the check part. If you didn¡¯t implement yet, add a ¡°TODO¡± remark
 * in code.<br>
 *
 * <li>there are some duplicate code need to be refactor.
 *
 * CommonFilterBlockProcessor.
 *
 */

public abstract class CommonFilterBlockProcessor extends BaseFilterBlockProcessor {

  CommonTree topAlias;
  CommonTree bottomAlias;
  CommonTree topTableRefElement;
  List<CommonTree> topAliasList;
  CommonTree closingSelect;
  CommonTree join;

  /**
   * make branch for top select
   */
  private void makeTop() {

    // create top select table ref node
    topTableRefElement = super.createTableRefElement(topSelect);
    topAlias = (CommonTree) topTableRefElement.getChild(0);

    // add alias for all top query select item
    topAliasList = super.buildSelectListAlias(topAlias, (CommonTree) topSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST));



    // create closing select
    closingSelect = super.createClosingSelect(topTableRefElement);
  }

  /**
   * make branch join
   *
   * @param joinType
   */
  private void makeJoin(CommonTree joinType) {
    // join
    join = super.createJoin(closingSelect);
    bottomAlias = super.buildJoin(joinType, join, bottomSelect);
  }

  /**
   * make branch for whole sub tree
   */
  private void makeEnd() {
    // closing select list
    CommonTree selectList = super.createSelectListForClosingSelect(topAliasList);
    if (selectList.getChildCount() == 0) {
      selectList = FilterBlockUtil.cloneSelectList((CommonTree) topSelect
          .getFirstChildWithType(PantheraExpParser.SELECT_LIST));
      for (int i = 0; i < selectList.getChildCount(); i++) {
        CommonTree selectItem = (CommonTree) selectList.getChild(i);
        CommonTree expr = (CommonTree) selectItem.getChild(0);
        expr.deleteChild(0);
        expr.addChild(super.createCascatedElement(FilterBlockUtil.cloneTree((CommonTree) selectItem
            .getChild(1).getChild(0))));
      }
    }
    FilterBlockUtil.attachChild(closingSelect, selectList);

    // set closing select to top select
    topSelect = closingSelect;
  }

  void rebuildTopQuery4Having() {

    // delete having. DO it before clone.
    FilterBlockUtil.deleteBranch((CommonTree) topQuery.getASTNode().getFirstChildWithType(
        PantheraParser_PLSQLParser.SQL92_RESERVED_GROUP),
        PantheraParser_PLSQLParser.SQL92_RESERVED_HAVING);

    // needn't group after transformed.
    super.topQuery.setGroup(null);

    super.topQuery.setHaving(true);

    // clone whole top select tree
    super.topSelect = super.topQuery.cloneWholeQuery();
  }

  /**
   * process compare operator with correlated
   *
   * @param joinType
   * @throws SqlXlateException
   */
  void processCompareC() throws SqlXlateException {

    this.makeTop();
    this.makeJoin(FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.CROSS_VK, "cross"));

    CommonTree compareKeyAlias1 = super.addSelectItem((CommonTree) topSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), (CommonTree) this.subQNode
        .getFirstChildWithType(PantheraParser_PLSQLParser.CASCATED_ELEMENT));

    // select list
    CommonTree compareKeyAlias2 = super.addAlias((CommonTree) ((CommonTree) bottomSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST)).getChild(0));// childCount==0;
    super.rebuildSelectListByFilter(false, true, bottomAlias, topAlias);

    this.makeEnd();

    // where
    super.buildWhereByFB(subQNode, compareKeyAlias1, compareKeyAlias2);

  }


  /**
   * process exists with correlated
   *
   * @param joinType
   * @throws SqlXlateException
   */
  void processExistsC() throws SqlXlateException {
    this.makeTop();


    this.makeJoin(FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.CROSS_VK,
        PantheraExpParser.LEFTSEMI_STR));


    super.processSelectAsterisk(bottomSelect);
    super.rebuildSelectListByFilter(false, false, bottomAlias, topAlias);

    this.makeEnd();
    super.buildWhereByFB(null, null, null);

    if (super.hasNotEqualCorrelated) {
      // become inner join if there is not equal correlated.
      join.deleteChild(0);
      // add distinct
      SqlXlateUtil.addCommonTreeChild(this.closingSelect, 1, FilterBlockUtil.createSqlASTNode(
          PantheraParser_PLSQLParser.SQL92_RESERVED_DISTINCT, "distinct"));
    }
  }

  /**
   * process compare operator with uncorrelated
   *
   * @param joinType
   */
  void processCompareUC() {

    // must have aggregation function in sub query
    this.processAggregationCompareUC();
  }

  private void processAggregationCompareUC() {

    this.makeTop();

    // add compare item
    CommonTree compareElement = super.getSubQOpElement();
    CommonTree cloneCompareElement = FilterBlockUtil.cloneTree(compareElement);

    CommonTree compareElementAlias = super.addSelectItem((CommonTree) topSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), cloneCompareElement);
    super.rebuildSubQOpElement(compareElement, compareElementAlias);

    this.makeJoin(FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.CROSS_VK, "cross"));

    // compare alias from subq
    CommonTree comparSubqAlias = super.addAlias((CommonTree) ((CommonTree) bottomSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST)).getChild(0));

    this.makeEnd();

    // where
    super.buildWhereBranch(bottomAlias, comparSubqAlias);
  }

  /**
   * process in with uncorrelated
   *
   * @param joinType
   */
  void processInUC() {

    this.makeTop();

    // add compare item
    // TODO multi parameter in sub query IN
    CommonTree compareElementAlias = super.addSelectItem((CommonTree) topSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), super.cloneSubQOpElement());

    this.makeJoin(FilterBlockUtil.createSqlASTNode(PantheraExpParser.LEFTSEMI_VK, "leftsemi"));

    // compare alias from subq
    CommonTree comparSubqAlias = super.addAlias((CommonTree) ((CommonTree) bottomSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST)).getChild(0));

    // on
    // FIXME which is first?
    CommonTree on = super.buildOn(FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.EQUALS_OP, "="), super.createCascatedElementWithTableName(
        (CommonTree) topAlias.getChild(0), (CommonTree) compareElementAlias.getChild(0)), super
        .createCascatedElementWithTableName((CommonTree) bottomAlias.getChild(0),
            (CommonTree) comparSubqAlias.getChild(0)));
    FilterBlockUtil.attachChild(join, on);

    this.makeEnd();
  }

  void processInC() throws SqlXlateException {

    this.makeTop();

    // add compare item
    // TODO multi parameter in sub query IN
    CommonTree compareElementAlias = super.addSelectItem((CommonTree) topSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), super.cloneSubQOpElement());

    this.makeJoin(FilterBlockUtil.createSqlASTNode(PantheraExpParser.LEFTSEMI_VK, "leftsemi"));

    // compare alias from subq
    CommonTree comparSubqAlias = super.addAlias((CommonTree) ((CommonTree) bottomSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST)).getChild(0));

    // on
    CommonTree leftChild = compareElementAlias.getType() == PantheraParser_PLSQLParser.ALIAS ? super
        .createCascatedElementWithTableName((CommonTree) topAlias.getChild(0),
            (CommonTree) compareElementAlias.getChild(0))
        : compareElementAlias;
    CommonTree on = super.buildOn(FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.EQUALS_OP, "="), leftChild, super
        .createCascatedElementWithTableName((CommonTree) bottomAlias.getChild(0),
            (CommonTree) comparSubqAlias.getChild(0)));
    FilterBlockUtil.attachChild(join, on);

    super.rebuildSelectListByFilter(false, false, bottomAlias, topAlias);
    this.makeEnd();
    super.buildWhereByFB(null, null, null);
  }

  /**
   * process not in with uncorrelated
   *
   * @param joinType
   * @throws SqlXlateException
   */
  void processNotInUC() throws SqlXlateException {
    CommonTree joinType = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.CROSS_VK,
        "cross");
    this.makeTop();


    // add compare item
    List<CommonTree> compareElementAlias = super.addSelectItems4In(topSelect, super.subQNode);


    this.makeJoin(joinType);
    super.rebuildCollectSet();

    // compare alias from subq
    List<CommonTree> comparSubqAlias = super.buildSelectListAlias(bottomAlias,
        (CommonTree) bottomSelect.getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST));


    this.makeEnd();

    // where
    // FIXME which is first?
    CommonTree where = super.buildWhere(FilterBlockUtil.dupNode(subQNode), comparSubqAlias,
        compareElementAlias);
    FilterBlockUtil.attachChild(closingSelect, where);
    super.rebuildArrayContains((CommonTree) where.getChild(0));
  }

  /**
   * process not exists with correlated
   *
   * @param joinType
   * @throws SqlXlateException
   */
  void processNotExistsC() throws SqlXlateException {
    // minuend tree
    CommonTree minuendSelect = FilterBlockUtil.cloneTree(topSelect);

    this.processExistsC();

    // not in
    super.subQNode = super.buildNotIn4Minus(minuendSelect, topSelect);
    bottomSelect = topSelect;
    topSelect = minuendSelect;
    // super.fbContext.setLogicTopSelect(topSelect);
    this.processNotInUC();
  }

  void processNotExistsCByLeftJoin() throws SqlXlateException {
    this.makeTop();
    super.joinTypeNode = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.CROSS_VK,
        PantheraExpParser.LEFT_STR);
    this.makeJoin(joinTypeNode);

    // for optimizing not equal condition
    // Map<joinType node,List<not equal condition node>>
    Map<CommonTree, List<CommonTree>> joinMap = (Map<CommonTree, List<CommonTree>>) super.context
        .getBallFromBasket(TranslateContext.JOIN_TYPE_NODE_BALL);
    if (joinMap == null) {
      joinMap = new HashMap<CommonTree, List<CommonTree>>();
      super.context.putBallToBasket(TranslateContext.JOIN_TYPE_NODE_BALL, joinMap);
    }

    joinMap.put(joinTypeNode, new ArrayList<CommonTree>());

    super.processSelectAsterisk(bottomSelect);
    super.rebuildSelectListByFilter(true, false, bottomAlias, topAlias);
    this.makeEnd();
    super.buildWhereByFB(null, null, null);
  }

  public void processExistsUC() {
    this.makeTop();
    this.makeJoin(FilterBlockUtil.createSqlASTNode(PantheraExpParser.CROSS_VK, "cross"));
    CommonTree limit = SqlXlateUtil.newSqlASTNode(PantheraExpParser.LIMIT_VK, "limit");
    bottomSelect.addChild(limit);
    CommonTree limitNum = SqlXlateUtil.newSqlASTNode(PantheraExpParser.UNSIGNED_INTEGER, "1");
    limit.addChild(limitNum);
    this.makeEnd();
  }

  public void processNotExistsUC() throws SqlXlateException {
    this.makeTop();
    bottomSelect = super.reCreateBottomSelect(super.createTableRefElement(bottomSelect), super
        .createCountAsteriskSelectList());
    this.makeJoin(FilterBlockUtil.createSqlASTNode(PantheraExpParser.CROSS_VK, "cross"));
    this.makeEnd();
    super.reBuildNotExist4UCWhere(topSelect, (CommonTree) this.bottomAlias.getChild(0));
  }
}
