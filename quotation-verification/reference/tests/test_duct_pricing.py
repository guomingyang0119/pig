from copy import deepcopy

import pytest

from quotation.duct import (DEFAULT_PAYMENT_TERMS, REQUIRED_RATES, calculate_duct,
                            validate_catalog)
from quotation.engine import RuleError


def catalog():
    rates = {key: "1" for key in REQUIRED_RATES}
    rates.update({
        "density_galvanized": "7.85", "density_stainless": "7.93",
        "galvanized_semi": "4", "galvanized_straight": "3", "galvanized_irregular": "6",
        "galvanized_1_5_straight": "4.5", "galvanized_1_5_irregular": "7.5",
        "galvanized_1_5_unspecified": "5.5",
        "galvanized_narrow_extra": "3", "stainless_semi": "12", "stainless_straight": "10",
        "stainless_irregular": "15", "closing_common": "6", "closing_angle": "10",
        "closing_round": "20", "closing_0_5_extra": "5", "accessory_per_sqm": "2",
        "corner_unit_price": "0.3", "clip_per_meter_price": "3",
        "common_punch": "1",
        "net_galvanized_common": "1.17", "net_galvanized_angle": "1.14", "net_stainless": "1.15",
        "net_round": "1.17",
        "material_tax_factor": "1.1", "installation_tax_factor": "1.01",
        "flange_area_factor": "1.72",
    })
    result = {
        "name": "测试风管指标", "source": "人工确认的测试数值", "company_id": "guizhou",
        "effective_from": "2020-01-01", "effective_to": "", "rates": rates,
        "materials": [
            {"material": "galvanized_40", "thickness": "0.5", "ton_price": "3815"},
            {"material": "galvanized_40", "thickness": "1.5", "ton_price": "3680"},
            {"material": "galvanized_40", "thickness": "2.0", "ton_price": "3680"},
            {"material": "stainless_304", "thickness": "0.5", "ton_price": "14444"},
            {"material": "stainless_304", "thickness": "2.0", "ton_price": "14444"},
        ],
        "flanges": [
            {"name": "30黑角铁法兰 计算档位7.5", "material": "black", "size": 30, "grade": "7.5",
             "ton_price": "3358", "processing_per_m": "3"},
            {"name": "40黑角铁法兰 计算档位12.5", "material": "black", "size": 40, "grade": "12.5",
             "ton_price": "3321.2", "processing_per_m": "3"},
            {"name": "50热镀锌角铁法兰 计算档位19.5", "material": "hot_galvanized", "size": 50, "grade": "19.5",
             "ton_price": "3588", "processing_per_m": "5"},
        ],
        "expressions": {
            "material_price": "thickness * density * 0.001 * ton_price",
            "body_sale": "(material_price + processing + closing + accessory_per_sqm) * area_factor",
            "flange_per_meter": "flange_grade * ton_price / 1000 / 6 + flange_processing",
            "flange_per_area": "flange_per_meter * flange_area_factor",
        },
    }
    validate_catalog(result)
    return {"id": "catalog", "family": "catalog", "kind": "duct_catalog", "version": 1,
            "status": "published", "body": result}


def quote():
    return {"pricing_mode": "duct_v1", "duct_options": {
        "area_basis": "open", "product_state": "semi", "shape": "", "zinc_layer": "40",
        "flange": "angle", "include_common_punch": None, "tax_included": False,
        "payment_option": "no_install_cash",
        "corner_count": "", "clip_meters": "",
        "include_installation": False, "installation_amount": "",
        "include_freight": False},
        "rows": [{"name": "镀锌钢板风管", "spec": "δ=0.5mm", "category": "风管",
                  "engineering_quantity": "1000", "attributes": {},
                  "source_issue": False, "excluded": False}]}


def compute(q, resource=None, formal=True):
    return calculate_duct(q, [resource or catalog()], {"formal_template_confirmed": formal})


def test_confirmed_half_finished_angle_example():
    result = compute(quote())
    row = result["rows"][0]
    assert row["status"] == "priced"
    assert row["unit_price"] == "31.4"
    assert row["amount"] == "31400.0"
    assert row["selected_flange"]["size"] == 30
    assert row["selected_flange"]["grade"] == "7.5"
    assert row["flange_per_area"] == "12.4"
    assert row["flange_per_area_base"] == "12.379700"
    assert result["total"] == "31400.0"
    assert result["fees"]["accessories"]["status"] == "not_applicable"
    assert next(t["value"] for t in row["trace"] if t["step"] == "按㎡角码卡扣/㎡") == "0"


def test_processing_type_is_independent_of_semi_or_finished_state():
    q = quote()
    q["duct_options"]["shape"] = "semi_unspecified"
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert row["unit_price"] == "31.4"
    assert row["selected_shape"] == "semi_unspecified"
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "4"

    q["rows"][0]["attributes"]["shape"] = "straight"
    row = compute(q)["rows"][0]
    assert row["selected_shape"] == "straight"
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "3"
    q["rows"][0]["attributes"]["shape"] = "irregular"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "6"

    q["rows"][0]["attributes"].pop("shape")
    q["duct_options"]["product_state"] = "finished"
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert row["selected_shape"] == "semi_unspecified"
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "4"
    assert next(t["value"] for t in row["trace"] if t["step"] == "成品合管费/㎡") == "15"

    q["duct_options"]["shape"] = "straight"
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "3"
    q["duct_options"]["shape"] = "irregular"
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "6"

    q["duct_options"].update(product_state="semi", shape="semi_unspecified")
    q["rows"][0].update(name="304不锈钢板风管")
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "12"
    q["rows"][0]["attributes"]["shape"] = "straight"
    assert next(t["value"] for t in compute(q)["rows"][0]["trace"] if t["step"] == "风管基础加工费/㎡") == "10"
    q["rows"][0]["attributes"]["shape"] = "irregular"
    assert next(t["value"] for t in compute(q)["rows"][0]["trace"] if t["step"] == "风管基础加工费/㎡") == "15"


def test_one_point_five_galvanized_processing_preserves_three_type_rates():
    q = quote()
    q["rows"][0]["spec"] = "δ=1.5mm"
    q["duct_options"]["shape"] = "semi_unspecified"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "5.5"

    q["rows"][0]["attributes"]["shape"] = "straight"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "4.5"

    q["rows"][0]["attributes"]["shape"] = "irregular"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "7.5"

    q["rows"][0]["attributes"].update(shape="semi_unspecified", model="400×120")
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "风管基础加工费/㎡") == "5.5"
    assert next(t["value"] for t in row["trace"] if t["step"] == "口型风管A+B≤520附加加工费/㎡") == "3"


def test_rectangular_a_plus_b_under_520_adds_three_for_all_states_and_shapes():
    for state, shape in (("semi", "semi_unspecified"), ("finished", "straight"), ("finished", "irregular")):
        q = quote()
        q["duct_options"].update(product_state=state, shape=shape, flange_30_meters="10", area_basis="net")
        q["rows"][0]["attributes"]["model"] = "250×160"
        row = compute(q)["rows"][0]
        assert row["status"] == "priced"
        assert next(t["value"] for t in row["trace"] if t["step"] == "口型风管A+B≤520附加加工费/㎡") == "3"


def test_rectangular_a_plus_b_equal_to_520_adds_small_duct_fee_but_521_does_not():
    q = quote()
    q["rows"][0]["attributes"]["model"] = "400×120"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "口型风管A+B≤520附加加工费/㎡") == "3"
    q["rows"][0]["attributes"]["model"] = "400×121"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "口型风管A+B≤520附加加工费/㎡") == "0"


def test_net_factor_applies_to_body_and_angle_flange_together():
    q = quote()
    q["duct_options"]["area_basis"] = "net"
    row = compute(q)["rows"][0]
    assert row["area_factor"] == "1.14"
    assert row["unit_price"] == "35.7"
    assert row["amount"] == "35700.0"


def test_common_flange_uses_punching_fee_and_no_angle_material():
    q = quote()
    q["duct_options"]["flange"] = "common"
    assert compute(q)["rows"][0]["status"] == "pending"
    assert compute(q)["total"] is None
    q["duct_options"]["include_common_punch"] = True
    row = compute(q)["rows"][0]
    assert row["selected_flange"] is None
    assert row["flange_basis"] == "common_punch"
    assert row["unit_price"] == "22.0"
    q["duct_options"]["area_basis"] = "net"
    assert compute(q)["rows"][0]["unit_price"] == "25.7"
    q["duct_options"]["include_common_punch"] = False
    row = compute(q)["rows"][0]
    assert row["unit_price"] == "24.5"
    assert row["flange_basis"] == "common_no_punch"
    assert next(t["value"] for t in row["trace"] if t["step"] == "共板打孔费/㎡") == "0"
    q["duct_options"]["area_basis"] = "open"
    assert compute(q)["rows"][0]["unit_price"] == "21.0"


def test_common_flange_punching_is_always_one_when_selected():
    q = quote()
    q["duct_options"].update(flange="common", include_common_punch=True)

    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "共板打孔费/㎡") == "1"

    q["rows"][0]["attributes"]["model"] = "400×120"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "共板打孔费/㎡") == "1"
    assert next(t["value"] for t in row["trace"] if t["step"] == "口型风管A+B≤520附加加工费/㎡") == "3"

    q["rows"][0]["attributes"]["model"] = "400×121"
    row = compute(q)["rows"][0]
    assert next(t["value"] for t in row["trace"] if t["step"] == "共板打孔费/㎡") == "1"


def test_finished_closing_and_accessory_charge():
    q = quote()
    q["duct_options"].update(product_state="finished", shape="straight", flange="common", include_common_punch=True)
    row = compute(q)["rows"][0]
    # 14.973875 material + 3 processing + 11 closing + 2 accessory + 1 punch
    assert row["unit_price"] == "32.0"
    q["duct_options"]["flange"] = "angle"
    row = compute(q)["rows"][0]
    assert row["unit_price"] == "45.4"


def test_actual_whole_quote_accessories_freight_tax_and_installation():
    q = quote()
    q["duct_options"].update(flange="common", include_common_punch=True,
                             corner_count="100", clip_meters="10",
                             include_freight=True, freight_amount="100",
                             include_installation=True, installation_amount="200",
                             tax_included=True, payment_option="with_install_cash")
    result = compute(q)
    assert result["rows"][0]["unit_price"] == "23.1"  # freight is inside the taxed material unit
    assert result["fees"]["accessories"]["amount"] == "63.0"
    assert result["subtotal"] == "23163.0"
    assert result["fees"]["freight"]["amount"] == "1100.0"
    assert "已计入各风管材料单价" in result["fees"]["freight"]["reason"]
    assert result["taxable_base"] == "21060.0"
    assert result["fees"]["tax"]["amount"] == "2105.0"
    assert result["total"] == "23365.0"


def test_freight_uses_sum_of_duct_engineering_area_and_catalog_rate():
    q = quote()
    q["duct_options"].update(include_freight=True, freight_amount="999")
    q["rows"][0]["engineering_quantity"] = "12.25"
    q["rows"].append({**q["rows"][0], "engineering_quantity": "3.75", "attributes": {}})
    resource = catalog()
    resource["body"]["rates"]["freight_per_sqm"] = "2"
    validate_catalog(resource["body"])
    result = compute(q, resource)
    assert result["fees"]["freight"]["amount"] == "32.0"
    assert result["total"] == "534.5"
    assert "基础运费2元/㎡已计入各风管材料单价" in result["fees"]["freight"]["reason"]


def test_freight_waits_for_all_billable_duct_areas():
    q = quote()
    q["duct_options"]["include_freight"] = True
    q["rows"].append({**q["rows"][0], "engineering_quantity": "", "attributes": {}})
    result = compute(q)
    assert result["fees"]["freight"]["status"] == "pending"
    assert result["fees"]["freight"]["amount"] is None
    assert result["total"] is None


def test_single_accessory_input_uses_only_entered_actual_quantity():
    q = quote()
    q["duct_options"].update(flange="common", include_common_punch=True)
    q["duct_options"]["corner_count"] = "100"
    result = compute(q)
    assert result["accessory_mode"] == "actual"
    assert result["fees"]["accessories"]["amount"] == "30.0"
    assert result["total"] == "20030.0"
    q["duct_options"]["corner_count"] = ""
    q["duct_options"]["clip_meters"] = "10"
    result = compute(q)
    assert result["fees"]["accessories"]["amount"] == "30.0"
    assert result["total"] == "20030.0"
    q["duct_options"]["clip_meters"] = ""
    assert compute(q)["accessory_mode"] == "per_sqm"
    assert compute(q)["total"] == "22000.0"


def test_legacy_single_accessory_choice_does_not_override_entered_quantity():
    q = quote()
    q["duct_options"].update(flange="common", include_common_punch=False,
                             corner_count="100", semi_accessory_choice="per_sqm")
    result = compute(q)
    assert result["accessory_mode"] == "actual"
    assert result["fees"]["accessories"]["amount"] == "30.0"


def test_angle_ignores_actual_accessory_inputs_and_never_adds_them():
    q = quote()
    q["duct_options"].update(corner_count="100", clip_meters="10", include_common_punch=True)
    result = compute(q)
    assert result["total"] == "31400.0"
    assert result["fees"]["accessories"]["amount"] == "0.0"
    assert result["accessory_mode"] == "not_applicable"
    q["duct_options"]["clip_meters"] = ""
    assert compute(q)["total"] == "31400.0"


def test_two_mm_defaults_to_40_black_angle_grade_12_5():
    q = quote()
    q["rows"][0]["spec"] = "δ=2.0mm"
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert row["selected_flange"]["material"] == "black"
    assert row["selected_flange"]["size"] == 40
    assert row["selected_flange"]["grade"] == "12.5"


def test_long_side_1600_uses_40_regardless_of_thickness():
    q = quote()
    q["rows"][0]["attributes"].update(width="1600", height="500")
    assert compute(q)["rows"][0]["selected_flange"]["size"] == 40


def test_customer_explicit_50_hot_galvanized_and_actual_meters_wins():
    q = quote()
    q["rows"][0]["attributes"].update(flange_material="hot_galvanized", flange_size="50",
                                       flange_grade="19.5", flange_meters="10")
    row = compute(q)["rows"][0]
    assert row["flange_basis"] == "actual_meters"
    assert row["flange_unit_price"] == "16.7"
    assert row["flange_amount"] == "167.0"
    assert row["amount"] == "19167.0"


def test_actual_angle_flange_meters_also_use_net_area_factor():
    q = quote()
    q["duct_options"]["area_basis"] = "net"
    q["rows"][0]["attributes"].update(flange_material="hot_galvanized", flange_size="50",
                                       flange_grade="19.5", flange_meters="10")
    row = compute(q)["rows"][0]
    assert row["area_factor"] == "1.14"
    assert row["unit_price"] == "21.6"
    assert row["flange_unit_price"] == "19.0"
    assert row["flange_amount"] == "190.0"
    assert row["amount"] == "21790.0"


def test_whole_quote_30_and_40_flange_meters_replace_area_conversion():
    q = quote()
    q["duct_options"].update(flange_30_meters="10", flange_40_meters="20")
    result = compute(q)
    row = result["rows"][0]
    assert row["flange_basis"] == "aggregate_meters"
    assert row["unit_price"] == "19.0"
    assert row["body_amount"] == "19000.0"
    assert row["flange_per_area"] is None
    assert [(item["size"], item["meters"], item["unit_price"], item["amount"])
            for item in result["aggregate_flanges"]] == [(30, "10", "7.2", "72.0"), (40, "20", "9.9", "198.0")]
    assert result["fees"]["aggregate_flanges"]["amount"] == "270.0"
    assert result["subtotal"] == "19270.0"
    assert result["total"] == "19270.0"


def test_whole_quote_angle_flange_meters_use_net_area_factor():
    q = quote()
    q["duct_options"].update(area_basis="net", flange_30_meters="10", flange_40_meters="20")
    result = compute(q)
    assert [(item["size"], item["unit_price"], item["amount"])
            for item in result["aggregate_flanges"]] == [(30, "8.2", "82.0"), (40, "11.3", "226.0")]
    assert result["rows"][0]["unit_price"] == "21.6"
    assert result["total"] == "21908.0"


def test_whole_quote_flange_meters_cannot_mix_with_row_meters():
    q = quote()
    q["duct_options"]["flange_30_meters"] = "10"
    q["rows"][0]["attributes"]["flange_meters"] = "2"
    result = compute(q)
    assert result["rows"][0]["status"] == "pending"
    assert "不能再填写法兰米数" in result["rows"][0]["reason"]


def test_round_flange_uses_customer_count_and_tax_specific_meter_price():
    q = quote()
    q["rows"][0]["attributes"].update(flange="round", model="Φ500", round_flange_count="10")
    result = compute(q)
    row = result["rows"][0]
    assert row["effective_flange"] == "round"
    assert row["round_flange_unit_price"] == "15.7"
    assert row["round_flange_amount"] == "157.0"
    assert row["amount"] == "19157.0"
    assert result["total"] == "19157.0"

    q["duct_options"]["tax_included"] = True
    result = compute(q)
    row = result["rows"][0]
    assert row["round_flange_unit_price"] == "18.8"
    assert row["round_flange_amount"] == "188.0"
    assert result["taxable_base"] == "19157.0"
    assert result["fees"]["tax"]["amount"] == "1931.0"
    assert result["total"] == "21088.0"


def test_round_flange_count_uses_net_area_factor():
    q = quote()
    q["duct_options"]["area_basis"] = "net"
    q["rows"][0]["attributes"].update(flange="round", model="Φ500", round_flange_count="10")
    row = compute(q)["rows"][0]
    assert row["round_flange_unit_price"] == "18.4"
    assert row["round_flange_amount"] == "184.0"
    assert row["unit_price"] == "22.2"
    assert row["amount"] == "22384.0"


def test_round_flange_without_count_uses_area_conversion_and_finished_closing_fee():
    q = quote()
    q["rows"][0]["attributes"].update(flange="round", model="Φ500")
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert row["flange_basis"] == "round_area"
    assert row["round_flange_area_unit_price"] == "27.0"
    assert row["unit_price"] == "46.0"
    assert row["amount"] == "46000.0"

    q["duct_options"]["area_basis"] = "net"
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert row["area_factor"] == "1.17"
    assert row["round_flange_area_unit_price"] == "31.6"
    assert row["unit_price"] == "53.8"

    q["duct_options"].update(area_basis="open", product_state="finished", shape="straight")
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert next(t["value"] for t in row["trace"] if t["step"] == "成品合管费/㎡") == "25"
    assert row["unit_price"] == "70.0"


def test_row_flange_explicitly_overrides_whole_quote_default():
    q = quote()
    q["duct_options"].update(flange="angle", include_common_punch=False)
    q["rows"][0]["attributes"]["flange"] = "common"
    row = compute(q)["rows"][0]
    assert row["status"] == "priced"
    assert row["effective_flange"] == "common"
    assert row["flange_basis"] == "common_no_punch"


def test_stainless_density_and_net_factor():
    q = quote()
    q["rows"][0].update(name="304不锈钢板风管", spec="δ=0.5mm")
    q["duct_options"].update(flange="common", include_common_punch=True, area_basis="net")
    row = compute(q)["rows"][0]
    assert row["area_factor"] == "1.15"
    assert row["unit_price"] == "83.1"


def test_mixed_galvanized_and_stainless_quote_is_blocked_for_manual_confirmation():
    q = quote()
    q["duct_options"].update(area_basis="net", flange="common", include_common_punch=True,
                             corner_count="100", clip_meters="10")
    q["rows"].append({**q["rows"][0], "name": "304不锈钢板风管",
                      "engineering_quantity": "100", "attributes": {}})
    result = compute(q)
    assert result["pending_count"] == 2
    assert result["total"] is None
    assert all(row["status"] == "pending" for row in result["rows"])
    assert all("同时包含镀锌风管和不锈钢风管" in row["reason"] for row in result["rows"])
    assert any("请拆分报价或人工确认" in issue for issue in result["issues"])
    assert result["fees"]["accessories"]["status"] == "pending"
    assert result["fees"]["accessories"]["amount"] == "0.0"


def test_actual_common_accessories_use_net_area_factor():
    q = quote()
    q["duct_options"].update(area_basis="net", flange="common", include_common_punch=True,
                             corner_count="100", clip_meters="10")
    result = compute(q)
    assert [(item["code"], item["unit_price"], item["amount"])
            for item in result["accessory_items"]] == [
                ("corner", "0.4", "40.0"), ("clip", "3.5", "35.0")]
    assert result["rows"][0]["unit_price"] == "23.4"
    assert result["total"] == "23475.0"


def test_no_catalog_never_falls_back_to_old_logic():
    result = calculate_duct(quote(), [], {"formal_template_confirmed": True})
    assert result["rows"][0]["status"] == "pending"
    assert result["total"] is None


def test_payment_terms_are_required_and_never_guessed():
    q = quote()
    q["duct_options"]["payment_option"] = ""
    result = compute(q)
    assert result["rows"][0]["status"] == "pending"
    assert "付款方式" in result["rows"][0]["reason"]
    assert result["payment"] is None
    assert result["total"] is None


def test_no_install_15_day_option_uses_1_05():
    q = quote()
    q["duct_options"]["payment_option"] = "no_install_days_15"
    result = compute(q)
    assert result["payment"]["coefficient"] == "1.05"
    assert result["rows"][0]["unit_price"] == "32.9"
    assert result["total"] == "32900.0"


def test_general_material_applies_payment_then_tax_inside_rounded_unit_price():
    q = quote()
    q["duct_options"].update(payment_option="no_install_days_15", tax_included=True)
    result = compute(q)
    row = result["rows"][0]
    assert row["untaxed_unit_price"] == "32.9"
    assert row["unit_price"] == "36.2"
    assert row["amount"] == "36200.0"
    assert result["fees"]["tax"]["amount"] == "3300.0"
    assert result["total"] == "36200.0"  # tax is already in the unit price and is not added twice


def test_round_flange_taxed_formula_uses_12_times_payment_coefficient():
    q = quote()
    q["duct_options"].update(payment_option="no_install_days_15", tax_included=True)
    q["rows"][0]["attributes"].update(flange="round", model="Φ500", round_flange_count="10")
    row = compute(q)["rows"][0]
    assert row["round_flange_untaxed_unit_price"] == "16.5"
    assert row["round_flange_unit_price"] == "19.8"  # 3.14×0.5×12×1.05 => 19.8
    assert row["round_flange_amount"] == "198.0"


def test_payment_coefficient_applies_to_all_supplied_materials_including_freight_but_not_installation():
    q = quote()
    q["duct_options"].update(payment_option="with_install_progress_80",
                             flange="common", include_common_punch=True,
                             corner_count="100", clip_meters="10", include_freight=True,
                             include_installation=True, installation_amount="200")
    result = compute(q)
    assert [(item["code"], item["unit_price"], item["amount"]) for item in result["accessory_items"]] == [
        ("corner", "0.3", "30.0"), ("clip", "3.3", "33.0")]
    assert result["fees"]["freight"]["unit_price"] == "1.1"
    assert result["fees"]["freight"]["amount"] == "1100.0"
    assert result["fees"]["installation"]["amount"] == "200.0"

    q = quote()
    q["duct_options"].update(payment_option="no_install_days_7",
                             flange_30_meters="10", flange_40_meters="20")
    result = compute(q)
    assert result["payment"]["coefficient"] == "1.04"
    assert all(item["unit_price"] != item["base_unit_price"] for item in result["aggregate_flanges"])


@pytest.mark.parametrize("payment_option,coefficient", [
    ("no_install_cash", "1.00"),
    ("no_install_days_7", "1.04"),
    ("no_install_days_15", "1.05"),
    ("no_install_days_30", "1.06"),
    ("no_install_days_45", "1.07"),
])
def test_no_install_fixed_payment_buttons(payment_option, coefficient):
    q = quote()
    q["duct_options"]["payment_option"] = payment_option
    assert compute(q)["payment"]["coefficient"] == coefficient


@pytest.mark.parametrize("payment_option,coefficient", [
    ("with_install_cash", "1.00"),
    ("with_install_progress_70", "1.20"),
    ("with_install_progress_80", "1.10"),
])
def test_with_install_fixed_payment_buttons(payment_option, coefficient):
    q = quote()
    q["duct_options"].update(include_installation=True, installation_amount="100",
                             payment_option=payment_option)
    result = compute(q)
    assert result["payment"]["coefficient"] == coefficient
    assert result["fees"]["installation"]["amount"] == "100.0"


def test_payment_option_must_match_installation_mode():
    q = quote()
    q["duct_options"].update(include_installation=True, installation_amount="100",
                             payment_option="no_install_cash")
    result = compute(q)
    assert result["payment"] is None
    assert result["total"] is None
    assert any("安装状态已变化" in issue for issue in result["issues"])


def test_base_material_unit_is_not_rounded_before_payment_coefficient():
    q = quote()
    q["duct_options"]["payment_option"] = "no_install_days_45"
    row = compute(q)["rows"][0]
    assert row["body_base_unit_price"] == "18.973875"
    # Raw combined price 31.353575 × 1.07 = 33.548... -> 33.5.
    # Rounding 31.353575 to 31.4 first would incorrectly produce 33.6.
    assert row["unit_price"] == "33.5"


def test_catalog_payment_options_are_complete_and_protected():
    body = catalog()["body"]
    body["payment_terms"] = deepcopy(DEFAULT_PAYMENT_TERMS)
    validate_catalog(body)
    body["payment_terms"][0].pop("coefficient")
    with pytest.raises((RuleError, TypeError, ValueError)):
        validate_catalog(body)

    body = catalog()["body"]
    body["payment_terms"] = deepcopy(DEFAULT_PAYMENT_TERMS)
    body["payment_terms"][0]["label"] = "擅自修改"
    with pytest.raises(RuleError, match="不可擅自修改"):
        validate_catalog(body)
