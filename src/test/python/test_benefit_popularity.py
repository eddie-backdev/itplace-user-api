"""Run the repository's native list query against an in-memory join fixture.

This checks join multiplicity only; it is not PostgreSQL integration validation.
"""
from pathlib import Path
import sqlite3

source = Path('src/main/java/com/itplace/userapi/benefit/repository/BenefitRepository.java').read_text()
start = source.index('SELECT b.* FROM benefit b')
query = source[start:source.index('"""', start)]
db = sqlite3.connect(':memory:')
db.create_function('CONCAT', -1, lambda *args: ''.join(str(x) for x in args if x is not None))
db.executescript('''
CREATE TABLE benefit(benefitId INTEGER PRIMARY KEY, partnerId INTEGER, mainCategory TEXT, benefitName TEXT, active BOOLEAN);
CREATE TABLE partner(partnerId INTEGER, partnerName TEXT, category TEXT);
CREATE TABLE benefitCarrierPolicy(benefitCarrierPolicyId INTEGER, benefitId INTEGER, carrier TEXT, usageType TEXT, description TEXT, manual TEXT, active BOOLEAN);
CREATE TABLE carrierTierBenefit(benefitCarrierPolicyId INTEGER, context TEXT);
CREATE TABLE favorite(userId INTEGER, benefitId INTEGER);
INSERT INTO partner VALUES(1,'partner','cafe');
INSERT INTO benefit VALUES(1,1,'basic','A: 2 favorites, 2 policies',1),(2,1,'basic','B: 3 favorites, 1 policy',1);
INSERT INTO benefitCarrierPolicy VALUES(10,1,'SKT','offline','','',1),(11,1,'KT','offline','','',1),(12,2,'SKT','offline','','',1);
INSERT INTO favorite VALUES(1,1),(2,1),(1,2),(2,2),(3,2);
''')
result = db.execute(query, dict(mainCategory=None, category=None, filter=None, keyword=None,
                              carrierFilterEnabled=False, carriers='SKT', sort='POPULARITY')).fetchall()
assert [row[0] for row in result] == [2, 1], result
print('PASS: popularity counts distinct users independently of carrier policy multiplicity')
