-- Fixture rendgraagrida: seluruh entitas, produk, domain dan transaksi berikut sintetis.
INSERT INTO legal_entities(code,name,currency) VALUES ('RGA-EU','rendgraagrida Lab Systems Europe','EUR'),('RGA-US','rendgraagrida Lab Systems US','USD');
INSERT INTO customers(code,name,segment,territory,domain,credit_status) VALUES ('CUST-001','Helion Research Institute','Research','Europe','helion.example','APPROVED'),('CUST-002','Northstar Analytics','Diagnostics','Europe','northstar.example','APPROVED'),('CUST-003','Mira Laboratory','Research','Europe','mira.example','PENDING');
INSERT INTO suppliers(code,name) VALUES ('SUP-001','Orion Synthetic Supplies'),('SUP-002','Vela Components');
INSERT INTO warehouses(code,name) VALUES ('WH-EU','European Demonstration Warehouse');
INSERT INTO products(sku,name,category,product_line,uom,price,cost,track_stock,tracking) VALUES
('RGA-TR120','rendgraagrida TR120 Analyzer','Equipment','TR Series','unit',18000,11000,true,'SERIAL'),
('RGA-SP40','rendgraagrida SP40 Spectrometer','Equipment','SP Series','unit',12000,7000,true,'SERIAL'),
('RGA-PL80','rendgraagrida PL80 Platform','Equipment','PL Series','unit',15000,9000,true,'SERIAL'),
('RGA-TR120-MOD','TR120 Analysis Module','Equipment','TR Series','unit',4500,2500,true,'NONE'),
('RGA-SAMPLE-100','Sample Preparation Pack','Consumables','Sample','pack',120,55,true,'LOT'),
('RGA-REAGENT-50','Synthetic Reagent Pack','Consumables','Reagents','pack',200,95,true,'LOT'),
('RGA-CAL-10','Calibration Standards','Consumables','Calibration','pack',160,70,true,'LOT'),
('RGA-TUBE-100','Sample Tube Pack','Consumables','Sample','pack',80,35,true,'LOT'),
('RGA-FILTER-20','Filter Pack','Consumables','Sample','pack',140,60,true,'LOT'),
('RGA-CONTROL-20','Synthetic Control Pack','Consumables','Calibration','pack',220,100,true,'LOT'),
('RGA-SVC-ANNUAL','Annual Instrument Coverage','Service','Service','year',2400,0,false,'NONE'),
('RGA-SVC-INSTALL','Installation Service','Service','Service','unit',800,0,false,'NONE'),
('RGA-SVC-TRAIN','Operator Training','Service','Service','unit',1200,0,false,'NONE'),
('RGA-SVC-REMOTE','Remote Support','Service','Service','year',960,0,false,'NONE'),
('RGA-SVC-PREVENT','Preventive Maintenance','Service','Service','unit',1500,0,false,'NONE');
INSERT INTO inventory(product_id,warehouse_id,on_hand,reserved,unit_cost) SELECT id,1,CASE WHEN category='Equipment' THEN 5 ELSE 100 END,0,cost FROM products WHERE track_stock;
INSERT INTO periods(starts_on,ends_on,status) VALUES ('2027-01-01','2027-12-31','OPEN');
INSERT INTO accounts(code,name,type) VALUES ('1000','Bank','ASSET'),('1100','Accounts receivable','ASSET'),('1200','Inventory','ASSET'),('1300','Input tax','ASSET'),('2000','Accounts payable','LIABILITY'),('2100','Goods received not invoiced','LIABILITY'),('2200','Output tax','LIABILITY'),('2300','Deferred service revenue','LIABILITY'),('3000','Opening equity','EQUITY'),('4000','Product revenue','REVENUE'),('5000','Cost of goods sold','EXPENSE');
INSERT INTO journals(id,number,source_type,source_id,posted_on,currency,description) VALUES (1,'J-1','OPENING','1','2027-01-01','EUR','Synthetic opening inventory');
INSERT INTO journal_lines(journal_id,account_id,debit,credit) SELECT 1,id,(SELECT sum(on_hand*unit_cost) FROM inventory),0 FROM accounts WHERE code='1200';
INSERT INTO journal_lines(journal_id,account_id,debit,credit) SELECT 1,id,0,(SELECT sum(on_hand*unit_cost) FROM inventory) FROM accounts WHERE code='3000';
SELECT setval(pg_get_serial_sequence('journals','id'),1,true);
INSERT INTO installed_instruments(serial,product_id,customer_id,status,installed_on) SELECT 'SIM-RGA-TR120-00001',id,1,'ACTIVE','2026-04-15' FROM products WHERE sku='RGA-TR120';
INSERT INTO service_contracts(number,customer_id,asset_id,coverage_start,coverage_end,product_id,status) SELECT 'SC-001',1,1,'2026-04-16','2027-04-15',id,'ACTIVE' FROM products WHERE sku='RGA-SVC-ANNUAL';
