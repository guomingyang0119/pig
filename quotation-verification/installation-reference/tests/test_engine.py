import copy
from decimal import Decimal

import pytest

from quotation.engine import RuleError, evaluate, calculate, calculate_row, active_catalog, validate_resource
from quotation.store import DEFAULT_POLICY


def test_decimal_and_safe_formulas():
    assert evaluate("0.1+0.2", {}) == Decimal("0.3")
    assert evaluate("max(14, cost / factor)", {"cost": "75", "factor": ".75"}) == 100
    assert evaluate("round(1.005, 2)", {}) == Decimal("1.01")
    assert str(evaluate("100/0.75", {})).startswith("133.333")
    for expression in ["__import__('os')", "x.real", "x[0]", "2**100", "True", "1/0", "[1,2]", "min()", "1e99999"]:
        with pytest.raises(RuleError): evaluate(expression, {"x": 1})


def test_no_rule_never_zero(product):
    result = calculate({"rows": [product]}, [], copy.deepcopy(DEFAULT_POLICY))
    assert result["rows"][0]["status"] == "pending"
    assert result["rows"][0]["amount"] is None
    assert result["total"] is None and not result["formal_ready"]
    assert result["pending_count"] == 1


def test_duplicate_rule_blocked(product, rule, policy):
    other = {**copy.deepcopy(rule), "id": "other", "family": "other"}
    result = calculate({"rows": [product]}, [rule, other], policy)
    assert "冲突" in result["rows"][0]["reason"]
    assert result["rows"][0]["amount"] is None


def test_order_freight_once_and_rounding(product, rule, policy):
    policy["fees"]["freight"] = {"mode": "formula", "expression": "fixed", "parameters": {"fixed": "8.1"}}
    result = calculate({"rows": [product, copy.deepcopy(product)]}, [rule], policy)
    assert result["subtotal"] == "400.00"
    assert result["fees"]["freight"]["amount"] == "8.1"
    assert result["total"] == "409"
    assert result["rounding_difference"] == "0.90"


def test_fees_only_use_uncovered_base(product, rule, policy):
    covered = copy.deepcopy(rule)
    covered.update(id="covered", family="covered")
    rule["body"]["match"] = {"material": "A"}
    covered["body"]["match"] = {"material": "B"}
    covered["body"]["coverage"]["freight"] = True
    a,b=copy.deepcopy(product),copy.deepcopy(product)
    a["attributes"]={"material":"A"}
    b["attributes"]={"material":"B"}
    policy["fees"]["freight"] = {"mode":"formula", "expression":"base * rate", "parameters":{"rate":"0.1"}}
    result = calculate({"rows": [a,b]}, [rule,covered], policy)
    assert result["fees"]["freight"]["base"] == "200.00"
    assert result["fees"]["freight"]["amount"] == "20.000"


def test_no_fallback_to_expired_or_retired_price(rule):
    newer = copy.deepcopy(rule)
    newer.update(id="v2", version=2)
    newer["body"]["effective_to"] = "2021-01-01"
    assert active_catalog([rule,newer]) == []
    newer["body"]["effective_to"] = ""
    newer["status"] = "retired"
    assert active_catalog([rule,newer]) == []
    newer["status"]="published"
    newer["body"]["effective_from"]="2099-01-01"
    assert active_catalog([rule,newer]) == [rule]


def test_candidate_selection_requires_explicit_confirmation(product, rule, policy):
    rule["body"]["inputs"]=[{"key":"power","label":"功率","type":"number","source":"input"},
                               {"key":"motor_cost","label":"电机成本","type":"number","source":"candidate"}]
    rule["body"]["selector"]=[{"key":"power","op":"ge"}]
    rule["body"]["cost_expression"]="motor_cost"
    product["attributes"]={"power":"1.5"}
    c={"id":"candidate","family":"candidate","version":1,"status":"published","kind":"candidate",
       "body":{"name":"测试配置","category":"百叶风口","effective_from":"2020-01-01","attributes":{"model":"test","power":"2.2","motor_cost":"75"}}}
    first=calculate_row(product,[rule,c],policy)
    assert first["status"]=="pending" and len(first["candidates"])==1
    product["candidate_id"]="candidate"
    second=calculate_row(product,[rule,c],policy)
    assert second["amount"]=="200.00"
    assert second["inputs"]["power"]=="1.5"
    assert second["selected_candidate"]["body"]["attributes"]["power"]=="2.2"
    assert product["attributes"]["power"]=="1.5"


def test_missing_candidate_cost_not_zero(product, rule, policy):
    rule["body"]["inputs"]=[{"key":"power","label":"功率","type":"number"}, {"key":"motor_cost","label":"电机成本","type":"number","source":"candidate"}]
    rule["body"]["selector"]=[{"key":"power","op":"eq"}]
    product["attributes"]={"power":"1.5"}
    product["candidate_id"]="c"
    c={"id":"c","body":{"name":"测试","category":"百叶风口","attributes":{"power":"1.5"}}}
    c["kind"]="candidate"
    result=calculate_row(product,[rule,c],policy)
    assert result["status"]=="pending" and "电机成本" in result["reason"]


def test_resource_requires_complete_coverage(rule):
    validate_resource("rule",rule["body"])
    rule["body"]["coverage"]={}
    with pytest.raises(RuleError): validate_resource("rule",rule["body"])


def test_pending_fees_and_template_block_formal(product, rule, policy):
    policy["fees"]["tax"]["mode"]="pending"
    result=calculate({"rows":[product]},[rule],policy)
    assert result["total"] is None and not result["formal_ready"]
    policy["fees"]["tax"]["mode"]="not_applicable"
    policy["formal_template_confirmed"]=False
    result=calculate({"rows":[product]},[rule],policy)
    assert result["total"]=="200" and not result["formal_ready"]
