import 'package:flutter_test/flutter_test.dart';

import 'package:image_crop_example/main.dart';

void main() {
  testWidgets('example app builds', (WidgetTester tester) async {
    await tester.pumpWidget(MyApp());
    await tester.pump();
    expect(find.text('Open Image'), findsOneWidget);
  });
}
