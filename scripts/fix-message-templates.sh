#!/bin/bash

# Batch fix script to add isReferenced: true to clinical resources in LinuxForHealth message templates
# TICKET-002 Fix

set -e

echo "🔧 Fixing LinuxForHealth message templates - Adding isReferenced: true"
echo ""

TEMPLATE_DIR="/Users/nagarajansankrithi/Documents/github/hl7omop/hl7v2-fhir-converter/src/main/resources/hl7/message"

cd "$TEMPLATE_DIR"

# List of files to fix
FILES=(
  "ADT_A03.yml"
  "ADT_A04.yml"
  "ADT_A08.yml"
  "ADT_A28.yml"
  "ADT_A31.yml"
)

for FILE in "${FILES[@]}"; do
  if [ ! -f "$FILE" ]; then
    echo "⚠️  File not found: $FILE"
    continue
  fi
  
  echo "Fixing $FILE..."
  
  # Fix AllergyIntolerance
  if grep -q "resourceName: AllergyIntolerance" "$FILE"; then
    # Add isReferenced: true after repeats: true for AllergyIntolerance
    sed -i.bak '/resourceName: AllergyIntolerance/,/additionalSegments:/ {
      /repeats: true/a\
    isReferenced: true
    }' "$FILE"
    echo "  ✅ Added isReferenced to AllergyIntolerance"
  fi
  
  # Fix Procedure (if exists)
  if grep -q "resourceName: Procedure" "$FILE"; then
    sed -i.bak2 '/resourceName: Procedure/,/additionalSegments:/ {
      /repeats: true/a\
    isReferenced: true
    }' "$FILE"
    echo "  ✅ Added isReferenced to Procedure"
  fi
  
  # Fix Coverage (if exists)
  if grep -q "resourceName: Coverage" "$FILE"; then
    sed -i.bak3 '/resourceName: Coverage/,/additionalSegments:/ {
      /repeats: true/a\
    isReferenced: true
    }' "$FILE"
    echo "  ✅ Added isReferenced to Coverage"
  fi
  
  # Remove backup files
  rm -f "$FILE.bak" "$FILE.bak2" "$FILE.bak3"
  
  echo "  ✅ $FILE fixed"
  echo ""
done

echo "✅ All ADT message templates fixed!"
echo ""
echo "Files modified:"
for FILE in "${FILES[@]}"; do
  echo "  - $FILE"
done

